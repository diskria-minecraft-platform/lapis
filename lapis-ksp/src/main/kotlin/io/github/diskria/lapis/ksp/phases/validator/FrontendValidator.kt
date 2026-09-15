package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.extensions.ks.isValid
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedPatch
import io.github.diskria.lapis.ksp.phases.parser.models.SymbolSource
import io.github.diskria.lapis.ksp.phases.validator.models.FunctionParameter
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.java.JPModifier
import javax.lang.model.element.Modifier
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class FrontendValidator(private val logger: Logger) {

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
        val mixinAnnotations = validateMixinAnnotations(annotations)
        kspRequire(isClass) { "235" }
        kspRequire(!isObject) { "236" }
        kspRequire(!isSealed) { "237" }
        kspRequire(!isOpen) { "238" }
        val constructor = kspRequireNotNull(constructors.singleOrNull()) { "239" }
        constructor.kspRequire(constructor.isPublic) { "240" }
        val constructorParameters = constructor.parameters.mapNotNull {
            runOrNullOnSkip { it.validate(targetClassDeclaration) }
        }
        val (parsedInjectionFunctions, parsedRegularFunctions) = functions.partition {
            validateMixinAnnotations(it.annotations).isNotEmpty()
        }
        val extensionProperties = bodyProperties.filter { it.hasExtensionAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsExtension(targetClassDeclaration) }
        }
        val extensionFunctions = parsedRegularFunctions.filter { it.hasExtensionAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsExtension(targetClassDeclaration) }
        }
        val shadowProperties = bodyProperties.filter { it.hasShadowAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsShadow() }
        }
        val shadowFunctions = parsedRegularFunctions.filter { it.hasShadowAnnotation }.mapNotNull {
            runOrNullOnSkip { it.validateAsShadow() }
        }
        val injections = parsedInjectionFunctions.mapNotNull {
            runOrNullOnSkip { it.validateAsInjection(isInCompanionObject = false, targetClassDeclaration) }
        }
        val companionObjects = companionObjects.mapNotNull {
            runOrNullOnSkip { it.validate() }
        }
        val companionObjectInjections = companionObjects.flatMap { companionObject ->
            companionObject.functions.mapNotNull {
                runOrNullOnSkip { it.validateAsInjection(isInCompanionObject = true, targetClassDeclaration) }
            }
        }
        val hasStaticHooksOnly = constructorParameters.isEmpty()
            && extensionProperties.isEmpty() && extensionFunctions.isEmpty()
            && shadowProperties.isEmpty() && shadowFunctions.isEmpty()
            && injections.all { it.isStatic }
        if (!hasStaticHooksOnly) {
            kspRequire(isAbstract) { "275" }
        }
        return Patch(
            symbol = symbol,
            classDeclaration = classDeclaration,
            name = name,
            env = env,
            initStrategy = initStrategy,
            isImplRequired = !hasStaticHooksOnly,
            targetClassDeclaration = targetClassDeclaration,
            constructorParameters = constructorParameters,
            duckSources = buildList {
                addAll(extensionProperties)
                addAll(extensionFunctions)
                addAll(shadowProperties)
                addAll(shadowFunctions)
            },
            injections = injections + companionObjectInjections,
            mixinAnnotations = mixinAnnotations,
        )
    }

    private fun ParsedPatch.CompanionObject.validate(): ParsedPatch.CompanionObject {
        kspRequire(isPublic) { "296" }
        return this
    }

    private fun ParsedPatch.Constructor.Parameter.validate(
        targetClassDeclaration: KSClassDeclaration?
    ): Patch.ConstructorParameter {
        validateType(type)
        return when {
            hasOriginAnnotation -> {
                val typeClassDeclaration = type.declaration as? KSClassDeclaration
                kspRequire(
                    validateClassDeclaration(typeClassDeclaration) == validateClassDeclaration(targetClassDeclaration)
                ) { "308" }
                kspRequire(type.arguments.none { it.variance != Variance.STAR }) { "309" }
                Patch.ConstructorParameter.Origin(typeClassDeclaration)
            }

            else -> kspError { "313" }
        }
    }

    private fun ParsedPatch.Property.validateAsExtension(
        targetClassDeclaration: KSClassDeclaration?,
    ): Patch.Extension.Property {
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
            receiverClassDeclaration = validateClassDeclaration(targetClassDeclaration),
        )
    }

    private fun ParsedPatch.Function.validateAsExtension(
        targetClassDeclaration: KSClassDeclaration?,
    ): Patch.Extension.Function {
        kspRequire(isPublic) { "342" }
        kspRequireNotNull(jvmName) { "343" }
        kspRequire(extensionReceiverClassDeclaration == null) { "361" }
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
            receiverClassDeclaration = validateClassDeclaration(targetClassDeclaration),
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
        kspRequire(extensionReceiverClassDeclaration == null) { "387" }
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

    private fun ParsedPatch.Function.validateAsInjection(
        isInCompanionObject: Boolean,
        targetClassDeclaration: KSClassDeclaration?,
    ): Patch.Injection {
        kspRequireNotNull(jvmName) { "408" }
        kspRequire(!hasTypeParameters) { "409" }
        kspRequire(!isOpen) { "410" }
        val mixinAnnotations = validateMixinAnnotations(annotations)
        if (isInCompanionObject) {
            kspRequire(extensionReceiverClassDeclaration == null) { "438" }
        } else if (extensionReceiverClassDeclaration != null) {
            kspRequire(extensionReceiverClassDeclaration == validateClassDeclaration(targetClassDeclaration)) { "441" }
        }
        return Patch.Injection(
            jvmName = jvmName,
            extensionReceiverClassDeclaration = extensionReceiverClassDeclaration,
            mixinAnnotations = mixinAnnotations,
            isStatic = isInCompanionObject,
            parameters = parameters.map { it.validateAsNativeInjectionParameter() },
            returnType = returnType,
        )
    }

    private fun ParsedPatch.Function.Parameter.validateAsNativeInjectionParameter(): Patch.Injection.Parameter {
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
        kspRequire(classDeclaration?.isValid == true) { "777" }
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
            classDeclaration = validateClassDeclaration(annotation.classDeclaration),
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
