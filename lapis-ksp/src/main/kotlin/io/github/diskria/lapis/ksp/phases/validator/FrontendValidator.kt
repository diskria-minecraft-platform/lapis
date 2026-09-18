package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.processing.KSBuiltIns
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedPatch
import io.github.diskria.lapis.ksp.phases.parser.models.SymbolSource
import io.github.diskria.lapis.ksp.phases.validator.models.FunctionParameter
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.TargetType
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.java.JPModifier
import javax.lang.model.element.Modifier
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class FrontendValidator(private val builtIns: KSBuiltIns, private val logger: Logger) {

    fun validate(patches: List<ParsedPatch>): List<Patch> =
        patches.mapNotNull {
            runOrNullOnSkip { it.validate() }
        }

    private fun ParsedPatch.validate(): Patch {
        kspRequireNotNull(name) { "213" }
        kspRequireNotNull(env) { "214" }
        kspRequireNotNull(initStrategy) { "214" }
        validateClassDeclaration(classDeclaration)
        kspRequire(classDeclaration.typeParameters.isEmpty()) { "216" }
        kspRequire(isTopLevel) { "217" }
        kspRequire(hasPackageName) { "218" }
        kspRequire(isPublic) { "219" }
        kspRequire(!isSealed) { "237" }
        kspRequire(!isOpen) { "238" }
        validateType(targetType)
        val (parsedInjectionFunctions, parsedRegularFunctions) = functions.partition {
            validateMixinAnnotations(it.annotations).isNotEmpty()
        }
        val extensionProperties = properties.filter { it.hasExtensionAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsExtension(targetType) }
        }
        val extensionFunctions = parsedRegularFunctions.filter { it.hasExtensionAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsExtension(targetType) }
        }
        val shadowProperties = properties.filter { it.hasShadowAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsShadow() }
        }
        val shadowFunctions = parsedRegularFunctions.filter { it.hasShadowAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsShadow() }
        }
        val injections = parsedInjectionFunctions.mapNotNull {
            runOrNullOnSkip { it.validateAsInjection(isStatic = false, targetType) }
        }
        val companionObject = companionObject?.validate()?.let { companionObject ->
            val injections = companionObject.functions.mapNotNull {
                runOrNullOnSkip { it.validateAsInjection(isStatic = true, targetType) }
            }
            Patch.CompanionObject(name = companionObject.name, injections = injections)
        }
        val classKind = if (isClass) {
            val constructor = kspRequireNotNull(constructors.singleOrNull()) { "239" }
            constructor.kspRequire(constructor.isPublic) { "240" }
            val constructorParameters = constructor.parameters.mapNotNull {
                runOrNullOnSkip { it.validate(targetType) }
            }
            Patch.Class(isAbstract, constructorParameters)
        } else if (isInterface) {
            Patch.Interface
        } else {
            kspError { "53" }
        }
        return Patch(
            symbol = symbol,
            classDeclaration = classDeclaration,
            name = name,
            env = env,
            initStrategy = initStrategy,
            classKind = classKind,
            targetType = targetType,
            duckSources = buildList {
                addAll(extensionProperties)
                addAll(extensionFunctions)
                if (isAbstract) {
                    addAll(shadowProperties)
                    addAll(shadowFunctions)
                }
            },
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = validateMixinAnnotations(annotations),
        )
    }

    private fun ParsedPatch.CompanionObject.validate(): ParsedPatch.CompanionObject {
        kspRequire(isPublic) { "296" }
        return this
    }

    private fun ParsedPatch.Constructor.Parameter.validate(targetType: KSType): Patch.Class.ConstructorParameter {
        validateType(type)
        return when {
            hasOriginAnnotation -> {
                kspRequireNotNull(name) { "110" }
                kspRequire(type.arguments.all { it.variance == Variance.STAR }) { "309" }
                Patch.Class.ConstructorParameter.Origin(
                    name = name,
                    type = validateTargetTypeCompatibility(type, targetType),
                )
            }

            else -> kspError { "313" }
        }
    }

    private fun ParsedPatch.Property.validateAsExtension(targetType: KSType): Patch.Extension.Property {
        validateType(type)
        kspRequireNotNull(getter) { "322" }
        kspRequireNotNull(getter.jvmName) { "323" }
        kspRequire(isPublic) { "324" }
        kspRequire(!hasExtensionReceiver) { "325" }
        kspRequire(!isOpen && !isAbstract) { "327" }
        return Patch.Extension.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "332" } else null,
            type = type,
            receiverType = validateTargetTypeCompatibility(targetType, targetType),
        )
    }

    private fun ParsedPatch.Function.validateAsExtension(targetType: KSType): Patch.Extension.Function {
        kspRequire(isPublic) { "342" }
        kspRequireNotNull(jvmName) { "343" }
        kspRequire(extensionReceiverType == null) { "361" }
        kspRequire(!isOpen && !isAbstract) { "346" }
        val parameters = parameters.map {
            FunctionParameter(
                name = kspRequireNotNull(it.name) { "349" },
                type = validateType(it.type),
            )
        }
        return Patch.Extension.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType,
            receiverType = validateTargetTypeCompatibility(targetType, targetType),
        )
    }

    private fun ParsedPatch.Property.validateAsShadow(): Patch.Shadow.Property {
        validateType(type)
        kspRequire(isPublic) { "365" }
        kspRequire(isAbstract) { "366" }
        kspRequire(!hasExtensionReceiver) { "367" }
        kspRequireNotNull(getter) { "368" }
        kspRequireNotNull(getter.jvmName) { "369" }
        validateMixinAnnotations(annotations)
        val mappingName = validateMappingName(explicitMappingName, name)
        val shadowModifiers = validateModifiers(shadowModifiers, isMethod = false)
        return Patch.Shadow.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "375" } else null,
            mappingName = mappingName,
            modifiers = shadowModifiers,
            type = type,
            mixinAnnotations = validateMixinAnnotations(getter.annotations),
        )
    }

    private fun ParsedPatch.Function.validateAsShadow(): Patch.Shadow.Function {
        kspRequire(isPublic) { "384" }
        kspRequireNotNull(jvmName) { "385" }
        kspRequire(isAbstract) { "386" }
        kspRequire(extensionReceiverType == null) { "387" }
        val parameters = parameters.map {
            FunctionParameter(
                name = kspRequireNotNull(it.name) { "390" },
                type = validateType(it.type),
            )
        }
        val mappingName = validateMappingName(explicitMappingName, name)
        val shadowModifiers = validateModifiers(shadowModifiers, isMethod = true)
        return Patch.Shadow.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType,
            mappingName = mappingName,
            mixinAnnotations = validateMixinAnnotations(annotations),
            modifiers = shadowModifiers,
        )
    }

    private fun ParsedPatch.Function.validateAsInjection(isStatic: Boolean, targetType: KSType): Patch.Injection {
        kspRequireNotNull(jvmName) { "408" }
        kspRequire(!hasTypeParameters) { "409" }
        kspRequire(!isOpen) { "410" }
        if (isStatic) {
            kspRequire(extensionReceiverType == null) { "438" }
        }
        return Patch.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType?.let { validateTargetTypeCompatibility(it, targetType) },
            mixinAnnotations = validateMixinAnnotations(annotations),
            isStatic = isStatic,
            parameters = parameters.map { it.validateAsInjectionParameter() },
            returnType = returnType,
        )
    }

    private fun ParsedPatch.Function.Parameter.validateAsInjectionParameter(): Patch.Injection.Parameter {
        kspRequireNotNull(name) { "620" }
        kspRequireNotNull(type) { "621" }
        return Patch.Injection.Parameter(name, type, validateMixinAnnotations(annotations))
    }

    @OptIn(ExperimentalContracts::class)
    private fun SymbolSource.validateType(type: KSType?): KSType {
        contract { returns() implies (type != null) }
        kspRequire(type?.isError == false) { "770" }
        return type
    }

    @OptIn(ExperimentalContracts::class)
    private fun SymbolSource.validateClassDeclaration(classDeclaration: KSClassDeclaration?): KSClassDeclaration {
        contract { returns() implies (classDeclaration != null) }
        kspRequire(classDeclaration?.validate(enableNewFeatures = true) == true) { "777" }
        return classDeclaration
    }

    private fun SymbolSource.validateModifiers(modifiers: List<Modifier>, isMethod: Boolean): Set<Modifier> {
        val set = modifiers.toSet()
        val allowed = if (isMethod) JavaModifiers.methodAllowed else JavaModifiers.fieldAllowed
        kspRequire(allowed.containsAll(set)) { "862" }
        kspRequire(set.count { it in JavaModifiers.visibilities } <= 1) { "863" }
        if (isMethod) {
            kspRequire(set.count { it in JavaModifiers.methodConflicts } <= 1) { "865" }
            if (JPModifier.ABSTRACT in set) {
                kspRequire(set.none { it in JavaModifiers.abstractIllegals }) { "867" }
            }
            if (Modifier.NATIVE in set) {
                kspRequire(Modifier.DEFAULT !in set) { "870" }
            }
        } else {
            if (Modifier.FINAL in set) {
                kspRequire(Modifier.VOLATILE !in set) { "874" }
            }
        }
        return set
    }

    private fun SymbolSource.validateMappingName(explicitName: String?, implicitName: String): String =
        if (explicitName != null) {
            kspRequire(explicitName.isNotEmpty()) { "913" }
            explicitName
        } else {
            implicitName
        }

    private fun SymbolSource.validateMixinAnnotations(annotations: List<ParsedAnnotation?>): List<MixinAnnotation> =
        annotations.map {
            kspRequireNotNull(it) { "285" }
        }.filter { !it.isLapisApi }.mapNotNull {
            runOrNullOnSkip { validateMixinAnnotation(it) }
        }

    private fun SymbolSource.validateMixinAnnotation(annotation: ParsedAnnotation): MixinAnnotation =
        MixinAnnotation(
            typeClassDeclaration = validateClassDeclaration(annotation.typeClassDeclaration),
            arguments = annotation.arguments.filter { it.isExplicit }.map { argument ->
                when (argument) {
                    is ParsedAnnotation.ScalarArgument -> MixinAnnotation.ScalarArgument(
                        name = argument.name,
                        value = validateMixinAnnotationArgumentValue(argument.value),
                    )

                    is ParsedAnnotation.ArrayArgument -> MixinAnnotation.ArrayArgument(
                        name = argument.name,
                        elements = argument.elements.map { validateMixinAnnotationArgumentValue(it) },
                    )
                }
            }
        )

    fun SymbolSource.validateMixinAnnotationArgumentValue(
        value: ParsedAnnotation.Argument.Value
    ): MixinAnnotation.Argument.Value = when (value) {
        is ParsedAnnotation.Argument.BooleanValue -> MixinAnnotation.Argument.BooleanValue(value.boolean)
        is ParsedAnnotation.Argument.ByteValue -> MixinAnnotation.Argument.ByteValue(value.byte)
        is ParsedAnnotation.Argument.ShortValue -> MixinAnnotation.Argument.ShortValue(value.short)
        is ParsedAnnotation.Argument.IntValue -> MixinAnnotation.Argument.IntValue(value.int)
        is ParsedAnnotation.Argument.LongValue -> MixinAnnotation.Argument.LongValue(value.long)
        is ParsedAnnotation.Argument.CharValue -> MixinAnnotation.Argument.CharValue(value.char)
        is ParsedAnnotation.Argument.FloatValue -> MixinAnnotation.Argument.FloatValue(value.float)
        is ParsedAnnotation.Argument.DoubleValue -> MixinAnnotation.Argument.DoubleValue(value.double)
        is ParsedAnnotation.Argument.StringValue -> MixinAnnotation.Argument.StringValue(value.string)
        is ParsedAnnotation.Argument.TypeValue -> MixinAnnotation.Argument.TypeValue(validateType(value.type))
        is ParsedAnnotation.Argument.EnumValue -> {
            MixinAnnotation.Argument.EnumValue(validateClassDeclaration(value.enumClassDeclaration), value.entryName)
        }

        is ParsedAnnotation.Argument.AnnotationValue -> {
            MixinAnnotation.Argument.AnnotationValue(validateMixinAnnotation(value.annotation))
        }
    }

    private fun SymbolSource.validateTargetTypeCompatibility(type: KSType, targetType: KSType): TargetType {
        kspRequire(type.isAssignableFrom(targetType)) { "441" }
        return TargetType(
            type = type,
            isInterface = (type.declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE,
            isAny = type == builtIns.anyType,
        )
    }

    @Suppress("unused")
    private inline fun SymbolSource.kspInfo(crossinline message: () -> String) {
        logger.info(message(), symbol)
    }

    @Suppress("unused")
    private inline fun SymbolSource.kspWarn(crossinline message: () -> String) {
        logger.warn(message(), symbol)
    }

    private inline fun SymbolSource.kspError(crossinline message: () -> String): Nothing {
        logger.error(message(), symbol)
        throw SkipSymbolSignal()
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun SymbolSource.kspRequire(condition: Boolean, crossinline message: () -> String) {
        contract { returns() implies condition }
        if (!condition) {
            kspError(message = message)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <T> SymbolSource.kspRequireNotNull(value: T?, crossinline message: () -> String): T {
        contract { returns() implies (value != null) }
        return value ?: kspError(message = message)
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "This call is redundant because the passed value is already non-nullable.",
        level = DeprecationLevel.ERROR
    )
    private inline fun <T : Any> SymbolSource.kspRequireNotNull(value: T, crossinline message: () -> String): Nothing {
        internalError("kspRequireNotNull() called with a non-nullable value.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Ambiguous call: use kspRequire() for Boolean conditions.",
        replaceWith = ReplaceWith("kspRequire(value, message)"),
        level = DeprecationLevel.ERROR,
    )
    private fun SymbolSource.kspRequireNotNull(value: Boolean?, message: () -> String): Nothing {
        internalError("kspRequireNotNull() called with a Boolean value. Use kspRequire() instead.")
    }

    private fun <R> runOrNullOnSkip(block: () -> R): R? =
        try {
            block()
        } catch (_: SkipSymbolSignal) {
            null
        }

    private class SkipSymbolSignal : Exception()
}

// todo user-friendly errors
