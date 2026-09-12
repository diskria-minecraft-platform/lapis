package io.github.diskria.lapis.ksp.phases.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.writeTo
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.extensions.common.lapisError
import io.github.diskria.lapis.ksp.extensions.jp.*
import io.github.diskria.lapis.ksp.extensions.kp.*
import io.github.diskria.lapis.ksp.logging.KspArguments
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.generator.builders.*
import io.github.diskria.lapis.ksp.phases.generator.models.GenExtensionPack
import io.github.diskria.lapis.ksp.phases.generator.models.GenExtensionPackAccumulator
import io.github.diskria.lapis.ksp.phases.generator.models.GenMixinConfig
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.lowering.models.common.*
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrLambdaTypeName
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import org.spongepowered.asm.mixin.*

class Generator(
    private val kspArguments: KspArguments,
    private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: Logger,
) {
    fun generate(patches: List<IrPatch>) {
        patches.forEach { patch ->
            val extensionPackAccumulator = GenExtensionPackAccumulator()
            patch.impl?.let { generatePatchImpl(it, patch) }
            patch.mixin.duckInterface?.let { generateMixinDuckInterface(it, extensionPackAccumulator) }
            generateMixin(patch.mixin, patch.className, patch.impl, extensionPackAccumulator)
            if (extensionPackAccumulator.isNotEmpty()) {
                generateExtensionPack(patch.className, extensionPackAccumulator)
            }
        }
        generateMixinConfig(patches.map { it.mixin })
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
            val staticBridgeSync = mutableListOf<Pair<IrMixinShadowEntry, GenJavaEntity>>()
            mixin.duckInterface?.let { duckInterface ->
                addSuperInterface(duckInterface.className)
                duckInterface.entries.forEach { entry ->
                    when (entry) {
                        is IrMixinDuckExtensionEntry -> {
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

                        is IrMixinShadowEntry -> {
                            val shadowMemberReference = when (entry) {
                                is IrMixinShadowProperty -> {
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

                                is IrMixinShadowFunction -> {
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
                                            is IrMixinDuckPropertyEntry.Getter -> {
                                                return_(shadowMemberReference.callFormat) { shadowMemberReference() }
                                            }

                                            is IrMixinDuckPropertyEntry.Setter -> {
                                                code_("${shadowMemberReference.callFormat} = %N") {
                                                    shadowMemberReference(); +kind.parameter
                                                }
                                            }

                                            is IrMixinDuckFunctionEntry -> {
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
                mixin.duckInterface?.entries?.any { it is IrMixinShadowEntry } == true && staticBridgeSync.isNotEmpty()
                && hasStaticInjections
            ) {
                val staticBridge = IrMixinStaticBridge(
                    originatingFiles = mixin.duckInterface.originatingFiles,
                    className = patchClassName.derived("StaticBridge"),
                    entries = staticBridgeSync.map { it.first },
                )
                generateStaticBridge(staticBridge, patchClassName.nested("Companion"), extensionPackAccumulator)
                buildJavaMethod("syncStaticBridge", visibility = IrVisibilityModifier.PRIVATE) {
                    addAnnotation<Unique>()
                    addModifiers(JPModifier.STATIC)
                    setBody {
                        staticBridgeSync.forEach { (entry, shadowMember) ->
                            when (entry) {
                                is IrMixinShadowProperty -> {
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

                                is IrMixinShadowFunction -> {
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
            val (duckInterfaceParameter, shadowEntries) = patch.mixin.duckInterface.let { duckInterface ->
                val shadowEntries = duckInterface?.entries?.filterIsInstance<IrMixinShadowEntry>().orEmpty()
                if (duckInterface != null && shadowEntries.isNotEmpty()) {
                    IrParameter("duck", duckInterface.className) to shadowEntries
                } else {
                    null to emptyList()
                }
            }
            val constructorParameters = impl.constructorParameters.map { parameter ->
                when (parameter) {
                    is IrPatchImplConstructorInstanceParameter -> {
                        IrParameter(instanceParameterName, parameter.className)
                    }

                    is IrPatchImplConstructorDuckInterfaceParameter -> {
                        duckInterfaceParameter ?: lapisError("Duck interface parameter cannot be null")
                    }
                }
            }
            if (constructorParameters.isNotEmpty()) {
                setConstructor(constructorParameters)
                duckInterfaceParameter?.let {
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
            duckInterfaceParameter?.let {
                shadowEntries.forEach { entry ->
                    when (entry) {
                        is IrMixinDuckPropertyEntry -> {
                            addProperty(buildKotlinProperty(entry.sourceName, entry.typeName) {
                                addModifiers(KPModifier.OVERRIDE)
                                setGetter {
                                    setBody {
                                        return_("%N.%N()") { +duckInterfaceParameter; +entry.getter.name }
                                    }
                                }
                                entry.setter?.let { setter ->
                                    setSetter {
                                        setParameters(setter.parameters)
                                        setBody {
                                            code_("%N.%N(%N)") {
                                                +duckInterfaceParameter; +setter.name; +setter.parameter
                                            }
                                        }
                                    }
                                }
                            })
                        }

                        is IrMixinDuckFunctionEntry -> {
                            addFunction(buildKotlinFunction(entry.sourceName) {
                                addModifiers(KPModifier.OVERRIDE)
                                setParameters(entry.parameters)
                                setReturnType(entry.returnTypeName)
                                setBody {
                                    code_("%N.%N(${entry.parameters.format})", isReturn = entry.isReturn) {
                                        +duckInterfaceParameter; +entry.name; entry.parameters.forEach { +it }
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
                is IrPatchImplConstructorDuckInterfaceParameter -> buildJavaCodeBlock("this")
            }
        }
        val initializerCodeBlock = buildJavaCodeBlock("new %T(${constructorArgumentCodeBlocks.format})") {
            +impl.className; constructorArgumentCodeBlocks.forEach { +it }
        }
        val isEagerStrategy = impl.initStrategy == InitStrategy.Eager
        val isSynchronizedStrategy = impl.initStrategy == InitStrategy.Synchronized
        val isThreadSafeStrategy = impl.initStrategy == InitStrategy.Volatile || isSynchronizedStrategy
        val patchField = buildJavaField(
            name = "patch",
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
                name = "patchLock",
                typeName = Object::class.asIrTypeName(),
                visibility = IrVisibilityModifier.PRIVATE,
            ) {
                addAnnotation<Unique>()
                addModifiers(JPModifier.FINAL)
                initializer(buildJavaCodeBlock("new %T()") { +Object::class })
            }.also(destination::addField)
        } else null
        val getOrInitPatchMethod = buildJavaMethod(
            name = "getOrInitPatch",
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
        }
        return buildJavaMethod(name, IrVisibilityModifier.PRIVATE) {
            if (injection.isStatic) {
                addModifiers(JPModifier.STATIC)
            }
            val annotations = injection.mixinAnnotations.map { buildMixinAnnotation(it) }
            val parameters = injection.parameters.map { parameter ->
                buildJavaParameter(parameter.name, parameter.typeName) {
                    addAnnotations(parameter.mixinAnnotations.map { buildMixinAnnotation(it) })
                }
            }
            val argumentCodeBlocks = buildList {
                injection.extensionReceiverClassName?.let { add(buildDoubleCastJavaCodeBlock(it)) }
                addAll(parameters.map { it.toCodeBlock() })
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
                buildJavaCodeBlock { invokeHook_() }
            }
        }
    }

    private fun generateMixinDuckInterface(
        duckInterface: IrMixinDuckInterface,
        extensionPackAccumulator: GenExtensionPackAccumulator,
    ) {
        generateJavaFile(duckInterface, aggregating = false) {
            addMethods(duckInterface.entries.flatMap { it.kinds }.map { kind ->
                buildJavaMethod(kind.name) {
                    addModifiers(JPModifier.ABSTRACT)
                    setParameters(kind.parameters)
                    setReturnType(kind.returnTypeName)
                }
            })
        }
        val extensionPackEntities = mutableListOf<GenKotlinEntity>()
        duckInterface.entries.filterIsInstance<IrMixinDuckExtensionEntry>().forEach { entry ->
            when (entry) {
                is IrMixinDuckExtensionProperty -> {
                    extensionPackEntities += buildKotlinProperty(entry.sourceName, entry.typeName) {
                        setReceiverType(entry.receiverTypeName)
                        setGetter {
                            addModifiers(KPModifier.INLINE)
                            setBody {
                                return_("(this as %T).%N()") { +duckInterface.className; +entry.getter.name }
                            }
                        }
                        entry.setter?.let { setter ->
                            setSetter {
                                addModifiers(KPModifier.INLINE)
                                setParameters(setter.parameters)
                                setBody {
                                    code_("(this as %T).%N(%N)") {
                                        +duckInterface.className; +setter.name; +setter.parameter
                                    }
                                }
                            }
                        }
                    }.let(::GenKotlinPropertyEntity)
                }

                is IrMixinDuckExtensionFunction -> {
                    extensionPackEntities += buildKotlinFunction(entry.sourceName) {
                        addModifiers(KPModifier.INLINE)
                        setReceiverType(entry.receiverTypeName)
                        setParameters(entry.parameters)
                        setReturnType(entry.returnTypeName)
                        setBody {
                            code_("(this as %T).%N(${entry.parameters.format})", isReturn = entry.isReturn) {
                                +duckInterface.className; +entry.name; entry.parameters.forEach { +it }
                            }
                        }
                    }.let(::GenKotlinFunctionEntity)
                }
            }
        }
        extensionPackAccumulator.accumulate(extensionPackEntities, duckInterface.originatingFiles)
    }

    class IrMixinStaticBridge(
        override val originatingFiles: List<KSFile>,
        override val className: IrClassName,
        val entries: List<IrMixinShadowEntry>,
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
                    is IrMixinDuckPropertyEntry.Getter -> {
                        IrLambdaTypeName.of(returnTypeName = kind.typeName)
                    }

                    is IrMixinDuckPropertyEntry.Setter -> {
                        IrLambdaTypeName.of(parameters = listOf(IrSetterParameter(kind.typeName)))
                    }

                    is IrMixinDuckFunctionEntry -> {
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
                    is IrMixinShadowProperty -> {
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

                    is IrMixinShadowFunction -> {
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
            val envClassNames = mixinBlueprints.groupBy({ it.env }, { it.className })
            configJson.encodeToString(GeneratedMixinsJson.of(kspArguments.mixinPackage, envClassNames))
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
