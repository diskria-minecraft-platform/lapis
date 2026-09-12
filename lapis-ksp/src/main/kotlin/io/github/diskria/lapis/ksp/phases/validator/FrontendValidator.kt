package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import io.github.diskria.lapis.ksp.common.JavaModifiers
import io.github.diskria.lapis.ksp.extensions.common.lapisError
import io.github.diskria.lapis.ksp.extensions.ks.isValid
import io.github.diskria.lapis.ksp.extensions.ks.toClassDeclaration
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.bootstrap.Options
import io.github.diskria.lapis.ksp.phases.parser.models.ParserResult
import io.github.diskria.lapis.ksp.phases.parser.models.common.*
import io.github.diskria.lapis.ksp.phases.parser.models.patches.*
import io.github.diskria.lapis.ksp.phases.validator.models.ValidatorResult
import io.github.diskria.lapis.ksp.phases.validator.models.common.*
import io.github.diskria.lapis.ksp.phases.validator.models.patches.*
import io.github.diskria.poetesse.java.JPModifier
import javax.lang.model.element.Modifier
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class FrontendValidator(
    private val logger: Logger,
    private val options: Options,
) {
    fun validate(result: ParserResult): ValidatorResult =
        ValidatorResult(
            patches = result.patches.mapNotNull {
                runOrNullOnSkip { it.validate() }
            },
        )

    private fun ParsedPatch.validate(): Patch {
        kspRequireNotNull(name) { "213" }
        kspRequireNotNull(initStrategy) { "214" }
        validateClassDeclaration(classDeclaration)
        kspRequire(classDeclaration.typeParameters.isEmpty()) { "216" }
        kspRequire(isTopLevel) { "217" }
        kspRequire(hasPackageName) { "218" }
        kspRequire(isPublic) { "219" }
        val mixinAnnotations = resolveMixinAnnotations(annotations)
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
            resolveMixinAnnotations(it.annotations).isNotEmpty()
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
            extensionSources = extensionProperties + extensionFunctions,
            shadowSources = shadowProperties + shadowFunctions,
            injections = injections + companionObjectInjections,
            mixinAnnotations = mixinAnnotations,
        )
    }

    private fun ParsedPatchCompanionObject.validate(): ParsedPatchCompanionObject {
        kspRequire(isPublic) { "296" }
        return this
    }

    private fun ParsedPatchConstructorParameter.validate(
        targetClassDeclaration: KSClassDeclaration?
    ): PatchConstructorParameter {
        validateType(type)
        return when {
            hasOriginAnnotation -> {
                val typeClassDeclaration = type.toClassDeclaration()
                kspRequire(
                    validateClassDeclaration(typeClassDeclaration) == validateClassDeclaration(targetClassDeclaration)
                ) { "308" }
                kspRequire(type.arguments.none { it.variance != Variance.STAR }) { "309" }
                PatchConstructorOriginParameter(typeClassDeclaration)
            }

            else -> skipWithError { "313" }
        }
    }

    private fun ParsedPatchProperty.validateAsExtension(
        targetClassDeclaration: KSClassDeclaration?,
    ): ExtensionProperty {
        validateType(type)
        kspRequireNotNull(getter) { "322" }
        kspRequireNotNull(getter.jvmName) { "323" }
        kspRequire(isPublic) { "324" }
        kspRequire(!hasExtensionReceiver) { "325" }
        kspRequire(!isOpen && !isAbstract) { "327" }
        return ExtensionProperty(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "332" } else null,
            type = type,
            receiverClassDeclaration = validateClassDeclaration(targetClassDeclaration),
        )
    }

    private fun ParsedPatchFunction.validateAsExtension(
        targetClassDeclaration: KSClassDeclaration?,
    ): ExtensionFunction {
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
        return ExtensionFunction(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType,
            receiverClassDeclaration = validateClassDeclaration(targetClassDeclaration),
        )
    }

    private fun ParsedPatchProperty.validateAsShadow(): ShadowProperty {
        validateType(type)
        kspRequire(isPublic) { "365" }
        kspRequire(isAbstract) { "366" }
        kspRequire(!hasExtensionReceiver) { "367" }
        kspRequireNotNull(getter) { "368" }
        kspRequireNotNull(getter.jvmName) { "369" }
        val mappingName = resolveMappingName(explicitMappingName, name)
        val shadowModifiers = resolveModifiers(shadowModifiers, isMethod = false)
        return ShadowProperty(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "375" } else null,
            mappingName = mappingName,
            modifiers = shadowModifiers,
            type = type,
            mixinAnnotations = resolveMixinAnnotations(getter.annotations),
        )
    }

    private fun ParsedPatchFunction.validateAsShadow(): ShadowFunction {
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
        val mappingName = resolveMappingName(explicitMappingName, name)
        val shadowModifiers = resolveModifiers(shadowModifiers, isMethod = true)
        return ShadowFunction(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType,
            mappingName = mappingName,
            mixinAnnotations = resolveMixinAnnotations(annotations),
            modifiers = shadowModifiers,
        )
    }

    private fun ParsedPatchFunction.validateAsInjection(
        isInCompanionObject: Boolean,
        targetClassDeclaration: KSClassDeclaration?,
    ): PatchInjection {
        kspRequireNotNull(jvmName) { "408" }
        kspRequire(!hasTypeParameters) { "409" }
        kspRequire(!isOpen) { "410" }
        val mixinAnnotations = resolveMixinAnnotations(annotations)
        if (isInCompanionObject) {
            kspRequire(extensionReceiverClassDeclaration == null) { "438" }
        } else if (extensionReceiverClassDeclaration != null) {
            kspRequire(extensionReceiverClassDeclaration == validateClassDeclaration(targetClassDeclaration)) { "441" }
        }
        return PatchNativeInjection(
            jvmName = jvmName,
            extensionReceiverClassDeclaration = extensionReceiverClassDeclaration,
            mixinAnnotations = mixinAnnotations,
            isStatic = isInCompanionObject,
            parameters = parameters.map { it.validateAsNativeInjectionParameter() },
            returnType = returnType,
        )
    }

    private fun ParsedPatchFunctionParameter.validateAsNativeInjectionParameter(): PatchNativeInjectionParameter {
        kspRequireNotNull(name) { "620" }
        kspRequireNotNull(type) { "621" }
        return PatchNativeInjectionParameter(name, type, resolveMixinAnnotations(annotations))
    }

    @OptIn(ExperimentalContracts::class)
    private fun SymbolSource.validateType(type: KSType?): KSType {
        contract { returns() implies (type != null) }
        kspRequire(type?.isValid == true) { "770" }
        return type
    }

    @OptIn(ExperimentalContracts::class)
    private fun SymbolSource.validateClassDeclaration(classDeclaration: KSClassDeclaration?): KSClassDeclaration {
        contract { returns() implies (classDeclaration != null) }
        kspRequire(classDeclaration?.isValid == true) { "777" }
        return classDeclaration
    }

    private fun SymbolSource.resolveModifiers(modifiers: List<Modifier>, isMethod: Boolean): Set<Modifier> {
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

    private fun SymbolSource.resolveMappingName(explicitName: String?, implicitName: String): String =
        if (explicitName != null) {
            kspRequire(explicitName.isNotEmpty()) { "913" }
            explicitName
        } else {
            implicitName
        }

    private fun SymbolSource.resolveMixinAnnotations(annotations: List<ParsedAnnotation>): List<MixinAnnotation> =
        annotations.filterNot { it.isSourceRetention }.mapNotNull {
            runOrNullOnSkip { resolveMixinAnnotation(it) }
        }

    private fun SymbolSource.resolveMixinAnnotation(annotation: ParsedAnnotation): MixinAnnotation {
        validateClassDeclaration(annotation.typeClassDeclaration)

        fun ParsedAnnotationArgumentValue.resolveValue(): MixinAnnotationArgumentValue = when (this) {
            is ParsedAnnotationBooleanArgumentValue -> MixinAnnotationBooleanArgumentValue(boolean)
            is ParsedAnnotationByteArgumentValue -> MixinAnnotationByteArgumentValue(byte)
            is ParsedAnnotationShortArgumentValue -> MixinAnnotationShortArgumentValue(short)
            is ParsedAnnotationIntArgumentValue -> MixinAnnotationIntArgumentValue(int)
            is ParsedAnnotationLongArgumentValue -> MixinAnnotationLongArgumentValue(long)
            is ParsedAnnotationCharArgumentValue -> MixinAnnotationCharArgumentValue(char)
            is ParsedAnnotationFloatArgumentValue -> MixinAnnotationFloatArgumentValue(float)
            is ParsedAnnotationDoubleArgumentValue -> MixinAnnotationDoubleArgumentValue(double)
            is ParsedAnnotationStringArgumentValue -> MixinAnnotationStringArgumentValue(string)
            is ParsedAnnotationClassTypeArgumentValue -> MixinAnnotationClassTypeArgumentValue(validateType(type))
            is ParsedAnnotationEnumArgumentValue -> {
                MixinAnnotationEnumArgumentValue(validateClassDeclaration(entryClassDeclaration))
            }

            is ParsedAnnotationEmbeddedAnnotationArgumentValue -> {
                MixinAnnotationEmbeddedAnnotationArgumentValue(resolveMixinAnnotation(embeddedAnnotation))
            }
        }
        return MixinAnnotation(
            typeClassDeclaration = annotation.typeClassDeclaration,
            arguments = annotation.arguments.filter { it.isExplicit }.map { argument ->
                when (argument) {
                    is ParsedAnnotationSingleArgument -> {
                        MixinAnnotationSingleArgument(argument.name, argument.value.resolveValue())
                    }

                    is ParsedAnnotationArrayArgument -> {
                        MixinAnnotationArrayArgument(argument.name, argument.values.map { it.resolveValue() })
                    }
                }
            }
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

    private inline fun SymbolSource.kspError(crossinline message: () -> String) {
        logger.error(message(), symbol)
    }

    @Suppress("UnusedReceiverParameter")
    private fun SymbolSource.skipSymbol(): Nothing = throw SkipSymbolSignal()

    private inline fun SymbolSource.skipWithError(crossinline message: () -> String): Nothing {
        kspError(message)
        skipSymbol()
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun SymbolSource.kspRequire(condition: Boolean, crossinline message: () -> String) {
        contract { returns() implies condition }
        if (!condition) {
            skipWithError(message = message)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <T> SymbolSource.kspRequireNotNull(value: T?, crossinline message: () -> String): T {
        contract { returns() implies (value != null) }
        return value ?: skipWithError(message = message)
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "This call is redundant because the passed value is already non-nullable.",
        level = DeprecationLevel.ERROR
    )
    private inline fun <T : Any> SymbolSource.kspRequireNotNull(value: T, crossinline message: () -> String): Nothing {
        lapisError("kspRequireNotNull() called with a non-nullable value.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Ambiguous call: use kspRequire() for Boolean conditions.",
        replaceWith = ReplaceWith("kspRequire(value, message)"),
        level = DeprecationLevel.ERROR,
    )
    private fun SymbolSource.kspRequireNotNull(value: Boolean?, message: () -> String): Nothing {
        lapisError("kspRequireNotNull() called with a Boolean value. Use kspRequire() instead.")
    }

    private fun <R> runOrNullOnSkip(block: () -> R): R? =
        try {
            block()
        } catch (_: SkipSymbolSignal) {
            null
        }

    private class SkipSymbolSignal : Exception()
}
