package io.github.diskria.lapis.ksp.phases.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.injector.ModifyReturnValue
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import com.llamalad7.mixinextras.sugar.Cancellable
import com.llamalad7.mixinextras.sugar.Local
import com.llamalad7.mixinextras.sugar.Share
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Op
import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.extensions.common.lapisError
import io.github.diskria.lapis.ksp.extensions.jp.*
import io.github.diskria.lapis.ksp.extensions.kp.*
import io.github.diskria.lapis.ksp.extensions.withInternalPrefix
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.bootstrap.Options
import io.github.diskria.lapis.ksp.phases.builtins.Builtins
import io.github.diskria.lapis.ksp.phases.builtins.LocalVarImplBuiltin
import io.github.diskria.lapis.ksp.phases.builtins.SimpleBuiltin
import io.github.diskria.lapis.ksp.phases.generator.builders.*
import io.github.diskria.lapis.ksp.phases.generator.models.GenExtensionPack
import io.github.diskria.lapis.ksp.phases.generator.models.GenExtensionPackAccumulator
import io.github.diskria.lapis.ksp.phases.generator.models.GenInternalPrefix.*
import io.github.diskria.lapis.ksp.phases.generator.models.GenMixinConfig
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.lapis.ksp.phases.lowering.asIrParameterizedTypeName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.lowering.models.common.*
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrLambdaTypeName
import io.github.diskria.lapis.ksp.phases.lowering.types.orVoid
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.*
import org.spongepowered.asm.mixin.injection.*
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

class Generator(
    private val options: Options,
    private val builtins: Builtins,
    private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: Logger,
) {
    fun generate(schemas: List<IrSchema>, patches: List<IrPatch>) {
        schemas.forEach { schema ->
            val extensionPackAccumulator = GenExtensionPackAccumulator()
            schema.descriptors.forEach { descriptor ->
                when (descriptor) {
                    is IrInvokableDescriptor -> with(descriptor) {
                        bodyWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                        callWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                        cancelWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                    }

                    is IrFieldDescriptor -> with(descriptor) {
                        fieldGetWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                        fieldSetWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                        arrayGetWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                        arraySetWrapperImpl?.let { generateDescriptorWrapperImpl(it, extensionPackAccumulator) }
                    }
                }
            }
            if (extensionPackAccumulator.isNotEmpty()) {
                generateExtensionPack(schema.className, extensionPackAccumulator)
            }
        }
        patches.forEach { patch ->
            val extensionPackAccumulator = GenExtensionPackAccumulator()
            patch.impl?.let { generatePatchImpl(it, patch) }
            patch.mixin.bridge?.let { generateMixinBridge(it, extensionPackAccumulator) }
            generateMixin(patch.mixin, patch.className, patch.impl, extensionPackAccumulator)
            if (extensionPackAccumulator.isNotEmpty()) {
                generateExtensionPack(patch.className, extensionPackAccumulator)
            }
        }
        generateMixinConfig(patches.map { it.mixin })
    }

    private fun <T : IrDescriptorWrapperImpl<T>> generateDescriptorWrapperImpl(
        impl: T,
        extensionPackAccumulator: GenExtensionPackAccumulator,
    ) {
        val superClassTypeName = builtins[impl.wrapperBuiltin].parameterizedBy(impl.descriptorClassName)
        val result = builtins.generateDescriptorWrapperImpl(impl, superClassTypeName)
        extensionPackAccumulator.accumulate(result.extensionPackEntities, impl.originatingFiles)
        generateKotlinFile(impl, aggregating = false, suppressNames = listOf("NOTHING_TO_INLINE")) {
            setConstructor(result.constructorParameters)
            addProperties(result.constructorParameters.map { it.toKotlinConstructorProperty() })
            addSuperInterface(superClassTypeName)
        }
    }

    private fun generateMixin(
        mixin: IrMixin,
        patchClassName: IrClassName,
        patchImpl: IrPatchImpl?,
        extensionPackAccumulator: GenExtensionPackAccumulator,
    ) {
        generateJavaFile(mixin, aggregating = false) {
            if (mixin.mixinAnnotations.isNotEmpty()) {
                addAnnotations(mixin.mixinAnnotations.map { buildMixinAnnotation(it) })
            } else {
                if (mixin.targetClassName == null) {
                    lapisError("Target internal name cannot be null")
                }
                addAnnotation<Mixin> {
                    setArgumentValue(Mixin::value, listOf(mixin.targetClassName))
                }
            }
            addModifiers(JPModifier.ABSTRACT)
            val patchImplEntity = patchImpl?.let { generatePatchInitializer(this, it) }
            val staticBridgeSync = mutableListOf<Pair<IrMixinBridgeShadowEntry, GenJavaEntity>>()
            mixin.bridge?.let { bridge ->
                addSuperInterface(bridge.className)
                bridge.entries.forEach { entry ->
                    when (entry) {
                        is IrMixinBridgeExtensionEntry -> {
                            addMethods(entry.kinds.map { kind ->
                                buildJavaMethod(kind.name) {
                                    addAnnotation<Override>()
                                    setParameters(kind.parameters)
                                    setReturnType(kind.returnTypeName)
                                    setBody {
                                        val patchImplFormat = patchImplEntity?.callFormat
                                            ?: lapisError("Patch impl entity cannot be null")
                                        code_(
                                            "$patchImplFormat.%L(${kind.parameters.format})",
                                            isReturn = kind.isReturn
                                        ) {
                                            patchImplEntity(); +kind.sourceJvmName; kind.parameters.forEach { +it }
                                        }
                                    }
                                }
                            })
                        }

                        is IrMixinBridgeShadowEntry -> {
                            val shadowMemberReference = when (entry) {
                                is IrMixinBridgeShadow -> {
                                    buildJavaField(entry.mappingName, entry.typeName, visibility = null) {
                                        if (entry.mixinAnnotations.isNotEmpty()) {
                                            addAnnotations(entry.mixinAnnotations.map { buildMixinAnnotation(it) })
                                        } else {
                                            if (entry.setter != null) {
                                                addAnnotation<Mutable>()
                                            }
                                            if (entry.isFinal) {
                                                addAnnotation<Final>()
                                            }
                                            addAnnotation<Shadow>()
                                        }
                                        addModifiers(*entry.modifiers.toTypedArray())
                                    }.also(::addField).let(::GenJavaFieldEntity)
                                }

                                is IrMixinBridgeShadowFunction -> {
                                    buildJavaMethod(
                                        name = entry.mappingName,
                                        visibility = if (entry.isStatic) IrVisibilityModifier.PUBLIC else null,
                                    ) {
                                        if (entry.mixinAnnotations.isNotEmpty()) {
                                            addAnnotations(entry.mixinAnnotations.map { buildMixinAnnotation(it) })
                                        } else {
                                            addAnnotation<Shadow>()
                                        }
                                        addModifiers(*entry.modifiers.toTypedArray())
                                        setParameters(entry.parameters)
                                        setReturnType(entry.returnTypeName)
                                        if (entry.isStatic) setStubBody()
                                    }.also(::addMethod).let { GenJavaMethodEntity(it, entry.parameters) }
                                }
                            }
                            if (entry.isStatic) {
                                staticBridgeSync += entry to shadowMemberReference
                            }
                            addMethods(entry.kinds.map { kind ->
                                buildJavaMethod(kind.name) {
                                    addAnnotation<Override>()
                                    setParameters(kind.parameters)
                                    setReturnType(kind.returnTypeName)
                                    setBody {
                                        when (kind) {
                                            is IrMixinBridgeProperty.Getter -> {
                                                return_(shadowMemberReference.callFormat) { shadowMemberReference() }
                                            }

                                            is IrMixinBridgeProperty.Setter -> {
                                                code_("${shadowMemberReference.callFormat} = %N") {
                                                    shadowMemberReference(); +kind.parameter
                                                }
                                            }

                                            is IrMixinBridgeFunctionEntry -> {
                                                code_(shadowMemberReference.callFormat, isReturn = kind.isReturn) {
                                                    shadowMemberReference()
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
            }
            val hasStaticInjections = mixin.injections.any { it.isStatic }
            val syncStaticBridgeMethod = if (
                mixin.bridge?.entries?.any { it is IrMixinBridgeShadowEntry } == true && staticBridgeSync.isNotEmpty()
                && hasStaticInjections
            ) {
                val staticBridge = IrMixinStaticBridge(
                    originatingFiles = mixin.bridge.originatingFiles,
                    className = patchClassName.derived("StaticBridge"),
                    entries = staticBridgeSync.map { it.first },
                )
                generateStaticBridge(staticBridge, patchClassName.nested("Companion"), extensionPackAccumulator)
                buildJavaMethod("syncStaticBridge".withInternalPrefix(), visibility = IrVisibilityModifier.PRIVATE) {
                    addAnnotation<Unique>()
                    addModifiers(JPModifier.STATIC)
                    setBody {
                        staticBridgeSync.forEach { (entry, shadowMember) ->
                            when (entry) {
                                is IrMixinBridgeShadow -> {
                                    code_("%T.%L = %L") {
                                        val lambdaCodeBlock = buildJavaCodeBlock {
                                            lambda_(expression = shadowMember.toCodeBlock(asCall = true))
                                        }
                                        +staticBridge.className; +entry.getter.sourceJvmName; +lambdaCodeBlock
                                    }
                                    entry.setter?.let { setter ->
                                        code_("%T.%L = %L") {
                                            val lambdaCodeBlock = buildJavaCodeBlock {
                                                lambda_(parameters = setter.parameters) {
                                                    code_("${shadowMember.callFormat} = %N") {
                                                        shadowMember(); +setter.parameter
                                                    }
                                                    return_("%T.INSTANCE") { +KPUnit.asIrClassName() }
                                                }
                                            }
                                            +staticBridge.className; +setter.sourceJvmName; +lambdaCodeBlock
                                        }
                                    }
                                }

                                is IrMixinBridgeShadowFunction -> {
                                    code_("%T.%L = %L") {
                                        val value = buildJavaCodeBlock {
                                            if (entry.hasBigArity || entry.returnTypeName != null) {
                                                add("%T::${shadowMember.referenceFormat}") {
                                                    +mixin.className; +shadowMember
                                                }
                                            } else {
                                                lambda_(parameters = entry.parameters) {
                                                    code_(shadowMember.callFormat) { shadowMember() }
                                                    return_("%T.INSTANCE") { +KPUnit.asIrClassName() }
                                                }
                                            }
                                        }
                                        +staticBridge.className; +entry.sourceJvmName; +value
                                    }
                                }
                            }
                        }
                    }
                }.also(::addMethod)
            } else null
            addMethods(mixin.injections.map {
                buildMixinInjectionMethod(it, patchClassName, patchImplEntity, syncStaticBridgeMethod)
            })
        }
    }

    private fun generatePatchImpl(impl: IrPatchImpl, patch: IrPatch) {
        generateKotlinFile(impl, aggregating = false) {
            val instanceParameterName = "instance"
            val (internalBridgeParameter, internalBridgeEntries) = patch.mixin.bridge.let { bridge ->
                val shadowEntries = bridge?.entries?.filterIsInstance<IrMixinBridgeShadowEntry>().orEmpty()
                if (bridge != null && shadowEntries.isNotEmpty()) {
                    IrParameter("internal", bridge.className) to shadowEntries
                } else {
                    null to emptyList()
                }
            }
            val constructorParameters = impl.constructorParameters.map { parameter ->
                when (parameter) {
                    is IrPatchImplConstructorInstanceParameter -> {
                        IrParameter(instanceParameterName, parameter.className)
                    }

                    is IrPatchImplConstructorInternalBridgeParameter -> {
                        internalBridgeParameter ?: lapisError("Internal bridge parameter cannot be null")
                    }
                }
            }
            if (constructorParameters.isNotEmpty()) {
                setConstructor(constructorParameters)
                internalBridgeParameter?.let {
                    addProperty(it.toKotlinConstructorProperty(IrVisibilityModifier.PRIVATE))
                }
            }
            setSuperClass(
                patch.className,
                constructorArguments = patch.constructorArguments.map { argument ->
                    when (argument) {
                        is IrPatchConstructorOriginArgument -> {
                            IrParameter(instanceParameterName, argument.className).toKotlinCodeBlock()
                        }
                    }
                }
            )
            internalBridgeParameter?.let {
                internalBridgeEntries.forEach { entry ->
                    when (entry) {
                        is IrMixinBridgeProperty -> {
                            addProperty(buildKotlinProperty(entry.sourceName, entry.typeName) {
                                addModifiers(KPModifier.OVERRIDE)
                                setGetter {
                                    setBody {
                                        return_("%N.%N()") { +internalBridgeParameter; +entry.getter.name }
                                    }
                                }
                                entry.setter?.let { setter ->
                                    setSetter {
                                        setParameters(setter.parameters)
                                        setBody {
                                            code_("%N.%N(%N)") {
                                                +internalBridgeParameter; +setter.name; +setter.parameter
                                            }
                                        }
                                    }
                                }
                            })
                        }

                        is IrMixinBridgeFunctionEntry -> {
                            addFunction(buildKotlinFunction(entry.sourceName) {
                                addModifiers(KPModifier.OVERRIDE)
                                setParameters(entry.parameters)
                                setReturnType(entry.returnTypeName)
                                setBody {
                                    code_("%N.%N(${entry.parameters.format})", isReturn = entry.isReturn) {
                                        +internalBridgeParameter; +entry.name; entry.parameters.forEach { +it }
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    private fun generatePatchInitializer(destination: JPTypeBuilder, impl: IrPatchImpl): GenJavaEntity {
        val constructorArgumentCodeBlocks = impl.constructorParameters.map { parameter ->
            when (parameter) {
                is IrPatchImplConstructorInstanceParameter -> buildDoubleCastJavaCodeBlock(parameter.className)
                is IrPatchImplConstructorInternalBridgeParameter -> buildJavaCodeBlock("this")
            }
        }
        val initializerCodeBlock = buildJavaCodeBlock("new %T(${constructorArgumentCodeBlocks.format})") {
            +impl.className; constructorArgumentCodeBlocks.forEach { +it }
        }
        val isEagerStrategy = impl.initStrategy == InitStrategy.Eager
        val isSynchronizedStrategy = impl.initStrategy == InitStrategy.Synchronized
        val isThreadSafeStrategy = impl.initStrategy == InitStrategy.Volatile || isSynchronizedStrategy
        val patchField = buildJavaField(
            name = "patch".withInternalPrefix(),
            typeName = impl.className,
            visibility = IrVisibilityModifier.PRIVATE,
        ) {
            addAnnotation<Unique>()
            if (isEagerStrategy) addModifiers(JPModifier.FINAL)
            if (isThreadSafeStrategy) addModifiers(JPModifier.VOLATILE)
            if (isEagerStrategy) {
                initializer(initializerCodeBlock)
            }
        }.also(destination::addField)
        if (isEagerStrategy) {
            return GenJavaFieldEntity(patchField)
        }
        val synchronizedLockField = if (isSynchronizedStrategy) {
            buildJavaField(
                name = "patchLock".withInternalPrefix(),
                typeName = Object::class.asIrTypeName(),
                visibility = IrVisibilityModifier.PRIVATE,
            ) {
                addAnnotation<Unique>()
                addModifiers(JPModifier.FINAL)
                initializer(buildJavaCodeBlock("new %T()") { +Object::class })
            }.also(destination::addField)
        } else null
        val getOrInitPatchMethod = buildJavaMethod(
            name = "getOrInitPatch".withInternalPrefix(),
            visibility = IrVisibilityModifier.PRIVATE
        ) {
            addAnnotation<Unique>()
            setReturnType(impl.className)
            setBody {
                fun GenJavaMethodBody.ifFieldNull_(body: Builder<IrJavaCodeBlock>) {
                    if_(buildJavaCodeBlock("%N == null") { +patchField }, body)
                }

                fun GenJavaMethodBody.initField_(value: JPCodeBlock) {
                    code_("%N = %L") { +patchField; +value }
                }
                if (isThreadSafeStrategy) {
                    val localName = "local"
                    code_("%T %L = %N") { +impl.className; +localName; +patchField }

                    fun GenJavaMethodBody.ifLocalNull_(body: Builder<IrJavaCodeBlock>) {
                        if_(buildJavaCodeBlock("%L == null") { +localName }, body)
                    }

                    fun GenJavaMethodBody.initLocal_(value: JPCodeBlock) {
                        code_(buildJavaCodeBlock("%L = %L") { +localName; +value })
                    }

                    ifLocalNull_ {
                        if (synchronizedLockField != null) {
                            synchronized_(synchronizedLockField.toCodeBlock()) {
                                initLocal_(patchField.toCodeBlock())
                                ifLocalNull_ {
                                    initLocal_(initializerCodeBlock)
                                    initField_(localName.toJavaCodeBlock())
                                }
                            }
                        } else {
                            initLocal_(initializerCodeBlock)
                            initField_(localName.toJavaCodeBlock())
                        }
                    }
                    return_(localName.toJavaCodeBlock())
                } else {
                    ifFieldNull_ {
                        initField_(initializerCodeBlock)
                    }
                    return_(patchField.toCodeBlock())
                }
            }
        }.also(destination::addMethod)
        return GenJavaMethodEntity(getOrInitPatchMethod)
    }

    private fun buildMixinInjectionMethod(
        injection: IrInjection,
        patchClassName: IrClassName,
        patchImplMember: GenJavaEntity?,
        syncStaticBridgeMethod: JPMethod?,
    ): JPMethod {
        val name = when (injection) {
            is IrNativeInjection -> injection.jvmName
            is IrHookInjection -> injection.jvmName + injection.ordinal?.let { "_ordinal${it}" }.orEmpty()
        }
        return buildJavaMethod(name, IrVisibilityModifier.PRIVATE) {
            val hasCancelArgument = injection is IrHookInjection
                && injection.hookArguments.any { it is IrHookCancelDescriptorWrapperImplArgument }
            if (injection.isStatic) {
                addModifiers(JPModifier.STATIC)
            }
            val (annotations, parameters, argumentCodeBlocks) = when (injection) {
                is IrNativeInjection -> {
                    val parameters = injection.parameters.map { parameter ->
                        buildJavaParameter(parameter.name, parameter.typeName) {
                            addAnnotations(parameter.mixinAnnotations.map { buildMixinAnnotation(it) })
                        }
                    }
                    Triple(
                        injection.mixinAnnotations.map { buildMixinAnnotation(it) },
                        parameters,
                        buildList {
                            injection.hookExtensionReceiverClassName?.let { add(buildDoubleCastJavaCodeBlock(it)) }
                            addAll(parameters.map { it.toCodeBlock() })
                        },
                    )
                }

                is IrHookInjection -> {
                    val receiverParameterName = "receiver".withInternalPrefix()
                    val valueParameterName = "value".withInternalPrefix()
                    val originalParameterName = "original".withInternalPrefix()
                    val callbackParameterName = "callback".withInternalPrefix()
                    val annotations = listOf(
                        when (injection) {
                            is IrWrapMethodHookInjection -> buildJavaAnnotation<WrapMethod> {
                                setArgumentValue(WrapMethod::method, listOf(injection.methodMixinReference))
                            }

                            is IrInjectHookInjection -> buildJavaAnnotation<Inject> {
                                setArgumentValue(Inject::method, listOf(injection.methodMixinReference))
                                setArgumentValue<Inject, At>(Inject::at) {
                                    setArgumentValue(
                                        At::value,
                                        when (injection) {
                                            is IrConstructorHeadHookInjection -> "CTOR_HEAD"
                                            is IrMethodHeadHookInjection -> "HEAD"
                                            is IrReturnHookInjection -> if (injection.isTail) "TAIL" else "RETURN"
                                        }
                                    )
                                    if (injection is IrConstructorHeadHookInjection) {
                                        setArgumentValue(At::args, injection.atArgs.map { "${it.first}=${it.second}" })
                                    }
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                                if (hasCancelArgument) {
                                    setArgumentValue(Inject::cancellable, true)
                                }
                            }

                            is IrModifyVariableHookInjection -> buildJavaAnnotation<ModifyVariable> {
                                setArgumentValue(ModifyVariable::method, listOf(injection.methodMixinReference))
                                when (val local = injection.local) {
                                    is IrNamedLocal -> setArgumentValue(ModifyVariable::name, listOf(local.name))
                                    is IrPositionalLocal -> setArgumentValue(ModifyVariable::ordinal, local.ordinal)
                                }
                                setArgumentValue<ModifyVariable, At>(ModifyVariable::at) {
                                    val atCode = when (injection.op) {
                                        Op.Get -> "LOAD"
                                        Op.Set -> "STORE"
                                    }
                                    setArgumentValue(At::value, atCode)
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrModifyReturnValueHookInjection -> buildJavaAnnotation<ModifyReturnValue> {
                                setArgumentValue(ModifyReturnValue::method, listOf(injection.methodMixinReference))
                                setArgumentValue<ModifyReturnValue, At>(ModifyReturnValue::at) {
                                    setArgumentValue(At::value, "RETURN")
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrWrapOperationHookInjection -> buildJavaAnnotation<WrapOperation> {
                                setArgumentValue(WrapOperation::method, listOf(injection.methodMixinReference))
                                setArgumentValue<WrapOperation, At>(WrapOperation::at) {
                                    setArgumentValue(At::value, if (injection.isConstructorCall) "NEW" else "INVOKE")
                                    setArgumentValue(At::target, injection.targetMixinReference)
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrModifyExpressionValueHookInjection -> buildJavaAnnotation<ModifyExpressionValue> {
                                setArgumentValue(ModifyExpressionValue::method, listOf(injection.methodMixinReference))
                                setArgumentValue<ModifyExpressionValue, At>(ModifyExpressionValue::at) {
                                    setArgumentValue(At::value, "CONSTANT")
                                    setArgumentValue(At::args, injection.atArgs.map { "${it.first}=${it.second}" })
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrFieldGetHookInjection, is IrFieldSetHookInjection -> buildJavaAnnotation<WrapOperation> {
                                setArgumentValue(WrapOperation::method, listOf(injection.methodMixinReference))
                                setArgumentValue<WrapOperation, At>(WrapOperation::at) {
                                    setArgumentValue(At::value, "FIELD")
                                    setArgumentValue(At::target, injection.targetMixinReference)
                                    val opcode = when (injection) {
                                        is IrFieldGetHookInjection -> {
                                            if (injection.isStaticTarget) Opcodes.GETSTATIC
                                            else Opcodes.GETFIELD
                                        }

                                        is IrFieldSetHookInjection -> {
                                            if (injection.isStaticTarget) Opcodes.PUTSTATIC
                                            else Opcodes.PUTFIELD
                                        }
                                    }
                                    setArgumentValue(At::opcode, opcode)
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrArrayHookInjection -> buildJavaAnnotation<Redirect> {
                                setArgumentValue(Redirect::method, listOf(injection.methodMixinReference))
                                setArgumentValue<Redirect, At>(Redirect::at) {
                                    setArgumentValue(At::value, "FIELD")
                                    setArgumentValue(At::target, injection.targetMixinReference)
                                    setArgumentValue(
                                        At::opcode,
                                        if (injection.isStaticTarget) Opcodes.GETSTATIC else Opcodes.GETFIELD
                                    )
                                    setArgumentValue(At::args, injection.atArgs.map { "${it.first}=${it.second}" })
                                    injection.ordinal?.let { setArgumentValue(At::ordinal, it) }
                                    setArgumentValue(At::unsafe, true)
                                }
                            }

                            is IrInstanceofHookInjection -> buildJavaAnnotation<WrapOperation> {
                                setArgumentValue(WrapOperation::method, listOf(injection.methodMixinReference))
                                setArgumentValue<WrapOperation, Constant>(WrapOperation::constant) {
                                    setArgumentValue(Constant::classValue, injection.className)
                                    injection.ordinal?.let { setArgumentValue(Constant::ordinal, it) }
                                }
                            }
                        })
                    val parameters = injection.parameters.map { parameter ->
                        when (parameter) {
                            is IrInjectionReceiverParameter -> {
                                buildJavaParameter(receiverParameterName, parameter.typeName) {
                                    if (parameter.isCoerce) {
                                        addAnnotation<Coerce>()
                                    }
                                }
                            }

                            is IrInjectionArgumentParameter -> {
                                buildJavaParameter(parameter.name.withInternalPrefix(ARGUMENT), parameter.typeName)
                            }

                            is IrInjectionOperationParameter -> {
                                buildJavaParameter(
                                    originalParameterName,
                                    Operation::class.asIrParameterizedTypeName(parameter.returnTypeName.orVoid())
                                )
                            }

                            is IrInjectionValueParameter -> buildJavaParameter(valueParameterName, parameter.typeName)

                            is IrInjectionLocalParameter -> {
                                val typeName = parameter.varImplBuiltin?.let {
                                    if (it == LocalVarImplBuiltin.ObjectLocalVar) {
                                        it.referenceTypeName.parameterizedBy(parameter.typeName)
                                    } else {
                                        it.referenceTypeName
                                    }
                                } ?: parameter.typeName
                                when (parameter) {
                                    is IrInjectionBodyLocalParameter -> {
                                        buildJavaParameter(parameter.name.withInternalPrefix(LOCAL), typeName) {
                                            addAnnotation<Local> {
                                                when (val local = parameter.local) {
                                                    is IrNamedLocal -> setArgumentValue(Local::name, listOf(local.name))
                                                    is IrPositionalLocal -> setArgumentValue(
                                                        Local::ordinal,
                                                        local.ordinal
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    is IrInjectionParamLocalParameter -> {
                                        buildJavaParameter(parameter.name.withInternalPrefix(PARAM), typeName) {
                                            addAnnotation<Local> {
                                                setArgumentValue(Local::index, parameter.localIndex)
                                                setArgumentValue(Local::argsOnly, true)
                                            }
                                        }
                                    }

                                    is IrInjectionShareParameter -> {
                                        buildJavaParameter(parameter.name.withInternalPrefix(SHARE), typeName) {
                                            addAnnotation<Share> {
                                                setArgumentValue(Share::value, parameter.key)
                                                parameter.namespace?.let { setArgumentValue(Share::namespace, it) }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrInjectionCallbackParameter -> {
                                buildJavaParameter(
                                    callbackParameterName,
                                    parameter.returnTypeName
                                        ?.let { CallbackInfoReturnable::class.asIrParameterizedTypeName(it) }
                                        ?: CallbackInfo::class.asIrTypeName()
                                ) {
                                    if (injection !is IrInjectHookInjection) {
                                        addAnnotation<Cancellable>()
                                    }
                                }
                            }
                        }
                    }
                    val argumentCodeBlocks = injection.hookArguments.map { argument ->
                        when (argument) {
                            is IrHookExtensionReceiverArgument -> buildDoubleCastJavaCodeBlock(argument.className)
                            is IrHookOriginValueArgument -> valueParameterName.toJavaCodeBlock()
                            is IrHookOriginDescriptorWrapperImplArgument<*> -> {
                                val constructorArgumentCodeBlocks = buildList {
                                    if (injection is IrTargetInjection
                                        && injection !is IrWrapMethodHookInjection
                                        && injection !is IrArrayHookInjection
                                        && !injection.isStaticTarget
                                    ) {
                                        add(receiverParameterName.toJavaCodeBlock())
                                    }
                                    if (injection is IrFieldSetHookInjection) {
                                        add("value".withInternalPrefix(ARGUMENT).toJavaCodeBlock())
                                    }
                                    if (injection is IrArrayHookInjection) {
                                        add("array".withInternalPrefix(ARGUMENT).toJavaCodeBlock())
                                        add("index".withInternalPrefix(ARGUMENT).toJavaCodeBlock())
                                        if (injection.op == Op.Set) {
                                            add("value".withInternalPrefix(ARGUMENT).toJavaCodeBlock())
                                        }
                                    }
                                    val impl = argument.wrapperImpl
                                    if (impl is IrInvokableDescriptorWrapperImpl) {
                                        addAll(impl.functionTypeParameters.mapIndexed { index, parameter ->
                                            (parameter.name ?: index.toString())
                                                .withInternalPrefix(ARGUMENT)
                                                .toJavaCodeBlock()
                                        })
                                    }
                                    if (injection !is IrArrayHookInjection) {
                                        add(originalParameterName.toJavaCodeBlock())
                                    }
                                }
                                buildJavaCodeBlock("new %T(${constructorArgumentCodeBlocks.format})") {
                                    +argument.wrapperImpl.className; constructorArgumentCodeBlocks.forEach { +it }
                                }
                            }

                            is IrHookCancelDescriptorWrapperImplArgument -> {
                                buildJavaCodeBlock("new %T(%L)") {
                                    +argument.wrapperImpl.className; +callbackParameterName
                                }
                            }

                            is IrHookOriginInstanceofWrapperImplArgument -> {
                                buildJavaCodeBlock("new %T(%L, %L)") {
                                    +builtins[SimpleBuiltin.Instanceof]; +valueParameterName; +originalParameterName
                                }
                            }

                            is IrHookOrdinalArgument -> injection.ordinal?.toJavaCodeBlock()
                                ?: lapisError("Ordinal cannot be null")

                            is IrHookLocalArgument -> {
                                val localName = argument.name.withInternalPrefix(
                                    when {
                                        argument.isBody -> LOCAL
                                        argument.isShare -> SHARE
                                        injection is IrInjectHookInjection -> ARGUMENT
                                        else -> PARAM
                                    }
                                )
                                argument.varBuiltin?.let {
                                    val typeFormat = if (it == LocalVarImplBuiltin.ObjectLocalVar) "%T<>" else "%T"
                                    buildJavaCodeBlock("new $typeFormat(%L)") { +builtins[it]; +localName }
                                } ?: localName.toJavaCodeBlock()
                            }
                        }
                    }
                    Triple(annotations, parameters, argumentCodeBlocks)
                }
            }
            addAnnotations(annotations)
            addParameters(parameters)
            setReturnType(injection.returnTypeName)
            setBody {
                if (injection.isStatic) {
                    syncStaticBridgeMethod?.let {
                        code_("%N()") { +it }
                    }
                }
                fun invokeHook_() {
                    val patchImplMember = if (injection.isStatic) null else {
                        patchImplMember ?: lapisError("Patch impl cannot be null")
                    }
                    val patchInstanceFormat = patchImplMember?.callFormat ?: "%T.Companion"
                    code_("$patchInstanceFormat.%L(${argumentCodeBlocks.format})", isReturn = injection.isReturn) {
                        patchImplMember?.let { it() } ?: +patchClassName
                        +injection.jvmName; argumentCodeBlocks.forEach { +it }
                    }
                }
                if (hasCancelArgument) {
                    try_(
                        block_ = { invokeHook_() },
                        catchingClassName = builtins[SimpleBuiltin.CancelSignal],
                        catch_ = injection.returnTypeName?.let {
                            {
                                val defaultValueCodeBlock = when (it.getJavaPrimitiveType(allowVoid = false)) {
                                    JPBoolean -> false.toJavaCodeBlock()
                                    JPByte, JPShort, JPInt -> 0.toJavaCodeBlock()
                                    JPLong -> 0L.toJavaCodeBlock()
                                    JPChar -> Char.MIN_VALUE.toJavaCodeBlock()
                                    JPFloat -> 0f.toJavaCodeBlock()
                                    JPDouble -> 0.0.toJavaCodeBlock()
                                    else -> null
                                }
                                return_(defaultValueCodeBlock ?: nullJavaCodeBlock)
                            }
                        },
                    )
                } else {
                    buildJavaCodeBlock { invokeHook_() }
                }
            }
        }
    }

    private fun generateMixinBridge(bridge: IrMixinBridge, extensionPackAccumulator: GenExtensionPackAccumulator) {
        generateKotlinFile(bridge, aggregating = false) {
            addFunctions(bridge.entries.flatMap { it.kinds }.map { kind ->
                buildKotlinFunction(kind.name) {
                    addModifiers(KPModifier.ABSTRACT)
                    setParameters(kind.parameters)
                    setReturnType(kind.returnTypeName)
                }
            })
        }
        val extensionPackEntities = mutableListOf<GenKotlinEntity>()
        bridge.entries.filterIsInstance<IrMixinBridgeExtensionEntry>().forEach { entry ->
            when (entry) {
                is IrMixinBridgeExtensionProperty -> {
                    extensionPackEntities += buildKotlinProperty(entry.sourceName, entry.typeName) {
                        setReceiverType(entry.receiverTypeName)
                        setGetter {
                            addModifiers(KPModifier.INLINE)
                            setBody {
                                return_("(this as %T).%N()") { +bridge.className; +entry.getter.name }
                            }
                        }
                        entry.setter?.let { setter ->
                            setSetter {
                                addModifiers(KPModifier.INLINE)
                                setParameters(setter.parameters)
                                setBody {
                                    code_("(this as %T).%N(%N)") { +bridge.className; +setter.name; +setter.parameter }
                                }
                            }
                        }
                    }.let(::GenKotlinPropertyEntity)
                }

                is IrMixinBridgeExtensionFunction -> {
                    extensionPackEntities += buildKotlinFunction(entry.sourceName) {
                        addModifiers(KPModifier.INLINE)
                        setReceiverType(entry.receiverTypeName)
                        setParameters(entry.parameters)
                        setReturnType(entry.returnTypeName)
                        setBody {
                            code_("(this as %T).%N(${entry.parameters.format})", isReturn = entry.isReturn) {
                                +bridge.className; +entry.name; entry.parameters.forEach { +it }
                            }
                        }
                    }.let(::GenKotlinFunctionEntity)
                }
            }
        }
        extensionPackAccumulator.accumulate(extensionPackEntities, bridge.originatingFiles)
    }

    class IrMixinStaticBridge(
        override val originatingFiles: List<KSFile>,
        override val className: IrClassName,
        val entries: List<IrMixinBridgeShadowEntry>,
    ) : IrKotlinClassBlueprint(KPTypeKind.OBJECT)

    private fun generateStaticBridge(
        bridge: IrMixinStaticBridge,
        patchCompanionClassName: IrClassName,
        extensionPackAccumulator: GenExtensionPackAccumulator,
    ) {
        val extensionPackEntities = mutableListOf<GenKotlinEntity>()
        generateKotlinFile(bridge, aggregating = false, suppressNames = listOf("NOTHING_TO_INLINE")) {
            addProperties(bridge.entries.flatMap { it.kinds }.map { kind ->
                val typeName = when (kind) {
                    is IrMixinBridgeProperty.Getter -> {
                        IrLambdaTypeName.of(returnTypeName = kind.typeName)
                    }

                    is IrMixinBridgeProperty.Setter -> {
                        IrLambdaTypeName.of(parameters = listOf(IrSetterParameter(kind.typeName)))
                    }

                    is IrMixinBridgeFunctionEntry -> {
                        if (kind.hasBigArity) {
                            val funInterfaceClassName = bridge.className.nested("Proxy_" + kind.sourceJvmName)
                            addType(buildKotlinInterface(funInterfaceClassName.simpleName) {
                                addModifiers(KModifier.PUBLIC, KModifier.FUN)
                                addFunction(buildKotlinFunction("invoke") {
                                    addModifiers(KPModifier.ABSTRACT, KPModifier.OPERATOR)
                                    setParameters(kind.parameters)
                                    setReturnType(kind.returnTypeName)
                                })
                            })
                            funInterfaceClassName
                        } else {
                            IrLambdaTypeName.of(
                                parameters = kind.parameters,
                                returnTypeName = kind.returnTypeName
                            )
                        }
                    }
                }
                buildKotlinProperty(kind.sourceJvmName, typeName) {
                    addModifiers(KModifier.LATEINIT)
                    mutable(true)
                }
            })
            bridge.entries.forEach { entry ->
                when (entry) {
                    is IrMixinBridgeShadow -> {
                        extensionPackEntities += buildKotlinProperty(entry.sourceName, entry.typeName) {
                            setReceiverType(patchCompanionClassName)
                            setGetter {
                                addModifiers(KPModifier.INLINE)
                                setBody {
                                    return_("%T.%L()") { +bridge.className; +entry.getter.sourceJvmName }
                                }
                            }
                            entry.setter?.let { setter ->
                                setSetter {
                                    addModifiers(KPModifier.INLINE)
                                    setParameters(setter.parameters)
                                    setBody {
                                        code_("%T.%L(%N)") {
                                            +bridge.className; +setter.sourceJvmName; +setter.parameter
                                        }
                                    }
                                }
                            }
                        }.let(::GenKotlinPropertyEntity)
                    }

                    is IrMixinBridgeShadowFunction -> {
                        extensionPackEntities += buildKotlinFunction(entry.sourceName) {
                            addModifiers(KPModifier.INLINE)
                            setReceiverType(patchCompanionClassName)
                            setParameters(entry.parameters)
                            setReturnType(entry.returnTypeName)
                            setBody {
                                code_("%T.%L(${entry.parameters.format})", isReturn = entry.isReturn) {
                                    +bridge.className; +entry.sourceJvmName; entry.parameters.forEach { +it }
                                }
                            }
                        }.let(::GenKotlinFunctionEntity)
                    }
                }
            }
        }
        extensionPackAccumulator.accumulate(extensionPackEntities, bridge.originatingFiles)
    }

    private fun generateExtensionPack(sourceClassName: IrClassName, accumulator: GenExtensionPackAccumulator) {
        val extensionPack = GenExtensionPack(
            originatingFiles = accumulator.originatingFiles,
            packageName = sourceClassName.packageName,
            fileName = sourceClassName.simpleName + "_Extensions",
        )
        generateKotlinFile(extensionPack, aggregating = false, suppressNames = listOf("NOTHING_TO_INLINE")) {
            accumulator.entities.forEach { entity ->
                when (entity) {
                    is GenKotlinPropertyEntity -> addProperty(entity.property)
                    is GenKotlinFunctionEntity -> addFunction(entity.function)
                }
            }
        }
    }

    private fun buildMixinAnnotation(annotation: IrMixinAnnotation): JPAnnotation =
        JPAnnotation.builder(annotation.className.java).apply {
            fun IrMixinAnnotationArgumentValue.buildValue(): JPCodeBlock = when (this) {
                is IrMixinAnnotationBooleanArgumentValue -> boolean.toJavaCodeBlock()
                is IrMixinAnnotationByteArgumentValue -> byte.toJavaCodeBlock()
                is IrMixinAnnotationShortArgumentValue -> short.toJavaCodeBlock()
                is IrMixinAnnotationIntArgumentValue -> int.toJavaCodeBlock()
                is IrMixinAnnotationLongArgumentValue -> long.toJavaCodeBlock()
                is IrMixinAnnotationCharArgumentValue -> char.toJavaCodeBlock()
                is IrMixinAnnotationFloatArgumentValue -> float.toJavaCodeBlock()
                is IrMixinAnnotationDoubleArgumentValue -> double.toJavaCodeBlock()
                is IrMixinAnnotationStringArgumentValue -> string.toJavaCodeBlock(asValue = true)
                is IrMixinAnnotationClassTypeArgumentValue -> typeName.toJavaCodeBlock(asClassType = true)
                is IrMixinAnnotationEnumArgumentValue -> entryClassName.toJavaCodeBlock()
                is IrMixinAnnotationEmbeddedAnnotationArgumentValue -> {
                    buildMixinAnnotation(embeddedAnnotation).toCodeBlock()
                }
            }
            annotation.arguments.forEach { argument ->
                val valueCodeBlock = when (argument) {
                    is IrMixinAnnotationSingleArgument -> {
                        argument.value.buildValue()
                    }

                    is IrMixinAnnotationArrayArgument -> {
                        val valueCodeBlocks = argument.values.map { it.buildValue() }
                        buildJavaCodeBlock("{${valueCodeBlocks.format}}") { valueCodeBlocks.forEach { +it } }
                    }
                }
                addMember(argument.name, valueCodeBlock)
            }
        }.build()

    private fun buildDoubleCastJavaCodeBlock(targetClassName: IrClassName): JPCodeBlock =
        if (targetClassName != KPAny.asIrClassName()) {
            buildJavaCodeBlock("(%T) (%T) this") { +targetClassName; +Object::class }
        } else {
            buildJavaCodeBlock("this")
        }

    private fun generateMixinConfig(mixinBlueprints: List<IrMixinRelatedBlueprint>) {
        val mixinConfig = GenMixinConfig(mixinBlueprints.flatMap { it.originatingFiles }, "mixins.json")
        generateResourceFile(mixinConfig, aggregating = true) {
            val qualifiedNames = mixinBlueprints.groupBy({ it.side }, { it.className })
            configJson.encodeToString(GeneratedMixinsJson.of(options.mixinPackage, qualifiedNames))
        }
    }

    private fun generateKotlinFile(
        kotlinFile: IrKotlinFileBlueprint,
        aggregating: Boolean,
        suppressNames: List<String> = emptyList(),
        builder: Builder<KPFileBuilder> = {}
    ) {
        val file = buildKotlinFile(kotlinFile.packageName, kotlinFile.fileName) {
            if (suppressNames.isNotEmpty()) {
                addAnnotation<Suppress> {
                    setArgumentValue(Suppress::names, *suppressNames.toTypedArray())
                }
            }
            builder()
        }
        file.writeTo(codeGenerator, aggregating, kotlinFile.originatingFiles)
    }

    private fun generateKotlinFile(
        kotlinFile: IrKotlinClassBlueprint,
        aggregating: Boolean,
        suppressNames: List<String> = emptyList(),
        builder: Builder<KPTypeBuilder> = {}
    ) {
        val name = kotlinFile.className.simpleName
        val file = buildKotlinFile(kotlinFile.className) {
            if (suppressNames.isNotEmpty()) {
                addAnnotation<Suppress> {
                    setArgumentValue(Suppress::names, *suppressNames.toTypedArray())
                }
            }
            addType(
                when (kotlinFile.typeKind) {
                    KPTypeKind.CLASS -> buildKotlinClass(name, builder = builder)
                    KPTypeKind.OBJECT -> buildKotlinObject(name, builder = builder)
                    KPTypeKind.INTERFACE -> buildKotlinInterface(name, builder = builder)
                }
            )
        }
        file.writeTo(codeGenerator, aggregating, kotlinFile.originatingFiles)
    }

    private fun generateJavaFile(
        javaFile: IrJavaFileBlueprint,
        aggregating: Boolean,
        suppressNames: List<String> = emptyList(),
        builder: Builder<JPTypeBuilder> = {}
    ) {
        val name = javaFile.className.simpleName
        val file = buildJavaFile(javaFile.className) {
            val builder: Builder<JPTypeBuilder> = {
                if (suppressNames.isNotEmpty()) {
                    addAnnotation<SuppressWarnings> {
                        setArgumentValue(SuppressWarnings::value, suppressNames)
                    }
                }
                builder()
            }
            when (javaFile.typeKind) {
                JPTypeKind.CLASS -> buildJavaClass(name, builder = builder)
                JPTypeKind.INTERFACE -> buildJavaInterface(name, builder = builder)
                else -> TODO()
            }
        }
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating, *javaFile.originatingFiles.toTypedArray()),
            packageName = javaFile.className.packageName.orEmpty(),
            fileName = name,
            extensionName = "java",
        ).writer().use { file.writeTo(it) }
    }

    private fun generateResourceFile(resource: IrResourceBlueprint, aggregating: Boolean, buildText: () -> String) {
        val text = buildText().trimEnd() + "\n"

        codeGenerator.createNewFileByPath(
            dependencies = Dependencies(aggregating, *resource.originatingFiles.toTypedArray()),
            path = "lapis-intermediates/${resource.fileName}",
            extensionName = "",
        ).writer().use { it.write(text) }
    }
}

private val configJson: Json = Json { prettyPrint = true }

fun PoetesseFile.writeWith(codeGenerator: CodeGenerator, dependencies: Dependencies = Dependencies.ALL_FILES) {
    codeGenerator
        .createNewFile(dependencies, packageName.orEmpty(), fileName, extensionName)
        .writer()
        .use(::writeTo)
}

fun PoetesseFile.writeWith(
    codeGenerator: CodeGenerator,
    aggregating: Boolean,
    originatingKSFiles: Iterable<KSFile>,
) {
    writeWith(codeGenerator, Dependencies(aggregating, *originatingKSFiles.toList().toTypedArray()))
}
