package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.ksp.KspLogger
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.parser.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.FunctionParameter
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.Type
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class FrontendValidator(private val options: KspOptions, private val logger: KspLogger) {

    fun validatePatches(patches: List<ParsedPatch>): List<Patch> =
        patches.filterValid { it.validate() }

    private fun ParsedPatch.validate(): Patch {
        kspRequire(classDeclaration.typeParameters.isEmpty()) { "216" }
        kspRequire(isTopLevel) { "217" }
        kspRequire(hasPackageName) { "218" }
        kspRequire(isPublic) { "219" }
        kspRequire(!isSealed) { "237" }
        kspRequire(!isOpen) { "238" }
        val kMixinAnnotation = kspRequireNotNull(annotations.findApiAnnotation<KMixin>()) { "" }
        val targetArgument = kspRequireNotNull(kMixinAnnotation.findArgument(KMixin::target)) { "" }
        val targetType = targetArgument.validate()
        val env = kspRequireNotNull(kMixinAnnotation.findArgument(KMixin::env)) { "" }
        val initStrategy = kspRequireNotNull(kMixinAnnotation.findArgument(KMixin::initStrategy)) { "" }
        val (injectionFunctions, regularFunctions) = functions.partition { function ->
            function.annotations.normalize().filterValid { it.validate() }.isNotEmpty()
        }
        val extensionProperties = properties.filter { it.annotations.findApiAnnotation<Extension>() != null }
            .filterValid { it.validateAsExtension(targetType) }
        val extensionFunctions = regularFunctions.filter { it.annotations.findApiAnnotation<Extension>() != null }
            .filterValid { it.validateAsExtension(targetType) }
        val kShadowProperties = properties.filter { it.annotations.findApiAnnotation<KShadow>() != null }
            .filterValid { it.validateAsShadow(isInterface) }
        val kShadowFunctions = regularFunctions.filter { it.annotations.findApiAnnotation<KShadow>() != null }
            .filterValid { it.validateAsShadow(isInterface) }
        val injections = injectionFunctions
            .filterValid { it.validateAsInjection(isInCompanionObject = false, targetType) }
        val companionObject = companionObject?.validate()?.let { companionObject ->
            val injections = companionObject.functions
                .filterValid { it.validateAsInjection(isInCompanionObject = true, targetType) }
            Patch.CompanionObject(name = companionObject.name, injections = injections)
        }
        val classKind = if (isClass) {
            val constructor = kspRequireNotNull(constructors.singleOrNull()) { "239" }
            constructor.kspRequire(constructor.isPublic) { "240" }
            val constructorParameters = constructor.parameters.filterValid { it.validate(targetType) }
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
            shadowSources = if (isAbstract || isInterface) kShadowProperties + kShadowFunctions else emptyList(),
            extensionSources = extensionProperties + extensionFunctions,
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = annotations.normalize().filterValid { it.validate() },
        )
    }

    private fun ParsedPatch.CompanionObject.validate(): ParsedPatch.CompanionObject {
        kspRequire(isPublic) { "296" }
        return this
    }

    private fun ParsedPatch.Constructor.Parameter.validate(targetType: Type) = when {
        annotations.findApiAnnotation<Origin>() != null -> Patch.Class.ConstructorParameter.Origin(
            name = name.validate(),
            type = validateTargetTypeCompatibility(type.validate(), targetType),
        )

        else -> kspError { "313" }
    }

    private fun ParsedPatch.Property.validateAsExtension(targetType: Type): Patch.Extension.Property {
        kspRequireNotNull(getter) { "322" }
        kspRequireNotNull(getter.jvmName) { "323" }
        kspRequire(isPublic) { "324" }
        kspRequire(!hasExtensionReceiver) { "325" }
        kspRequire(!isOpen && !isAbstract) { "327" }
        return Patch.Extension.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "332" } else null,
            type = type.validate(),
            receiverType = validateTargetTypeCompatibility(targetType, targetType),
        )
    }

    private fun ParsedPatch.Function.validateAsExtension(targetType: Type): Patch.Extension.Function {
        kspRequire(isPublic) { "342" }
        kspRequireNotNull(jvmName) { "343" }
        kspRequire(extensionReceiverType == null) { "361" }
        kspRequire(!isOpen && !isAbstract) { "346" }
        val parameters = parameters.map {
            FunctionParameter(
                name = it.name.validate(),
                type = it.type.validate(),
            )
        }
        return Patch.Extension.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType.validate(),
            receiverType = validateTargetTypeCompatibility(targetType, targetType),
        )
    }

    private fun ParsedPatch.Property.validateAsShadow(isInterface: Boolean): Patch.Shadow.Property {
        kspRequire(isPublic) { "365" }
        kspRequire(isAbstract) { "366" }
        kspRequire(!hasExtensionReceiver) { "367" }
        kspRequireNotNull(getter) { "368" }
        kspRequireNotNull(getter.jvmName) { "369" }
        val explicitMappingName = annotations.findApiAnnotation<MappingName>()?.findArgument(MappingName::name)
        val shadowModifiers = annotations.findApiAnnotation<KShadow>()?.findArgument(KShadow::modifiers).orEmpty()
        return Patch.Shadow.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "375" } else null,
            mappingName = validateMappingName(explicitMappingName, name),
            modifiers = validateModifiers(shadowModifiers, isInterface, isField = true),
            type = type.validate(),
            mixinAnnotations = getter.annotations.normalize().filterValid { it.validate() },
        )
    }

    private fun ParsedPatch.Function.validateAsShadow(isInterface: Boolean): Patch.Shadow.Function {
        kspRequire(isPublic) { "384" }
        kspRequireNotNull(jvmName) { "385" }
        kspRequire(isAbstract) { "386" }
        kspRequire(extensionReceiverType == null) { "387" }
        val explicitMappingName = annotations.findApiAnnotation<MappingName>()?.findArgument(MappingName::name)
        val shadowModifiers = annotations.findApiAnnotation<KShadow>()?.findArgument(KShadow::modifiers).orEmpty()
        return Patch.Shadow.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters.map { FunctionParameter(name = it.name.validate(), type = it.type.validate()) },
            returnType = returnType.validate(),
            mappingName = validateMappingName(explicitMappingName, name),
            mixinAnnotations = annotations.normalize().filterValid { it.validate() },
            modifiers = validateModifiers(shadowModifiers, isInterface, isField = false),
        )
    }

    private fun ParsedPatch.Function.validateAsInjection(
        isInCompanionObject: Boolean,
        targetType: Type,
    ): Patch.Injection {
        kspRequireNotNull(jvmName) { "408" }
        kspRequire(!hasTypeParameters) { "409" }
        kspRequire(!isOpen) { "410" }
        if (isInCompanionObject) {
            kspRequire(extensionReceiverType == null) { "438" }
        }
        return Patch.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType?.let {
                validateTargetTypeCompatibility(it.validate(), targetType)
            },
            mixinAnnotations = annotations.normalize().filterValid { it.validate() },
            parameters = parameters.map { it.validateAsInjectionParameter() },
            returnType = returnType.validate(),
        )
    }

    private fun ParsedPatch.Function.Parameter.validateAsInjectionParameter() = Patch.Injection.Parameter(
        name = name.validate(),
        type = type.validate(),
        mixinAnnotations = annotations.normalize().filterValid { it.validate() },
    )

    context(parent: SymbolSource)
    private fun ParsedName.validate(): String {
        parent.kspRequire(this is ValidName) { "228" }
        return name
    }

    context(parent: SymbolSource)
    private fun ParsedType.validate(): Type {
        parent.kspRequire(this is ValidType) { "770" }
        return Type(type, isInterface, isAny)
    }

    private fun SymbolSource.validateClassDeclaration(classDeclaration: KSClassDeclaration?): KSClassDeclaration {
        kspRequire(classDeclaration?.validate(enableNewFeatures = true) == true) { "777" }
        return classDeclaration
    }

    private fun SymbolSource.validateModifiers(
        rawModifiers: List<Modifier>,
        isInterface: Boolean,
        isField: Boolean,
    ): EnumSet<Modifier> {
        val result = if (rawModifiers.isEmpty()) EnumSet.noneOf(Modifier::class.java) else EnumSet.copyOf(rawModifiers)
        if (isInterface) {
            if (PRIVATE !in result && PROTECTED !in result) {
                result.add(PUBLIC)
            }
            if (isField) {
                result.add(STATIC)
                result.add(FINAL)
            } else if (DEFAULT !in result && STATIC !in result && PRIVATE !in result) {
                result.add(ABSTRACT)
            }
        }
        val allowed = if (isField) JavaModifiers.FIELD_ALLOWED else JavaModifiers.METHOD_ALLOWED
        kspRequire(allowed.containsAll(result)) { "862" }
        kspRequire(result.count { it in JavaModifiers.VISIBILITIES } <= 1) { "863" }
        if (isField) {
            if (FINAL in result) {
                kspRequire(VOLATILE !in result) { "874" }
            }
        } else {
            if (ABSTRACT in result) {
                kspRequire(result.none { it in JavaModifiers.ABSTRACT_ILLEGALS }) { "865" }
            }
            if (NATIVE in result) {
                kspRequire(DEFAULT !in result) { "870" }
            }
            if (isInterface) {
                if (PRIVATE in result) {
                    kspRequire(DEFAULT !in result) { "866" }
                    kspRequire(ABSTRACT !in result) { "867" }
                } else {
                    kspRequire(result.count { it in EnumSet.of(ABSTRACT, STATIC, DEFAULT) } == 1) { "868" }
                }
            } else {
                kspRequire(DEFAULT !in result) { "869" }
            }
        }
        return result
    }

    private fun SymbolSource.validateMappingName(explicitName: String?, implicitName: String): String =
        if (explicitName != null) {
            kspRequire(explicitName.isNotEmpty()) { "913" }
            explicitName
        } else {
            implicitName
        }

    private fun ParsedAnnotations.normalize() = external.filter {
        // TODO: Filter annotations by mixin packages from options
        true
    }

    private fun ParsedAnnotation.validate(): MixinAnnotation {
        kspRequire(this is ValidAnnotation) { "303" }
        return MixinAnnotation(
            typeClassDeclaration = validateClassDeclaration(typeClassDeclaration),
            arguments = arguments.normalize().filterValid { it.validate() },
        )
    }

    private fun List<ParsedAnnotation.Argument>.normalize() =
        filter { it !is ParsedAnnotation.ValidArgument || it.isExplicit }

    private fun ParsedAnnotation.Argument.validate(): MixinAnnotation.Argument {
        kspRequire(this is ParsedAnnotation.ValidArgument) { "322" }
        return when (this) {
            is ParsedAnnotation.ScalarArgument -> MixinAnnotation.ScalarArgument(name, value.validate())
            is ParsedAnnotation.ArrayArgument -> {
                MixinAnnotation.ArrayArgument(name, elements.filterValid { it.validate() })
            }
        }
    }

    context(parent: SymbolSource)
    private fun ParsedAnnotation.Argument.Value.validate(): MixinAnnotation.Argument.Value {
        parent.kspRequire(this is ParsedAnnotation.Argument.ValidValue) { "313" }
        return when (this) {
            is ParsedAnnotation.Argument.BooleanValue -> MixinAnnotation.Argument.BooleanValue(boolean)
            is ParsedAnnotation.Argument.ByteValue -> MixinAnnotation.Argument.ByteValue(byte)
            is ParsedAnnotation.Argument.ShortValue -> MixinAnnotation.Argument.ShortValue(short)
            is ParsedAnnotation.Argument.IntValue -> MixinAnnotation.Argument.IntValue(int)
            is ParsedAnnotation.Argument.LongValue -> MixinAnnotation.Argument.LongValue(long)
            is ParsedAnnotation.Argument.CharValue -> MixinAnnotation.Argument.CharValue(char)
            is ParsedAnnotation.Argument.FloatValue -> MixinAnnotation.Argument.FloatValue(float)
            is ParsedAnnotation.Argument.DoubleValue -> MixinAnnotation.Argument.DoubleValue(double)
            is ParsedAnnotation.Argument.StringValue -> MixinAnnotation.Argument.StringValue(string)
            is ParsedAnnotation.Argument.TypeValue -> MixinAnnotation.Argument.TypeValue(type.validate())
            is ParsedAnnotation.Argument.EnumValue -> {
                MixinAnnotation.Argument.EnumValue(parent.validateClassDeclaration(enumClassDeclaration), entryName)
            }

            is ParsedAnnotation.Argument.AnnotationValue -> {
                MixinAnnotation.Argument.AnnotationValue(annotation.validate())
            }
        }
    }

    private fun SymbolSource.validateTargetTypeCompatibility(type: Type, targetType: Type): Type {
        kspRequire(type.type.isAssignableFrom(targetType.type)) { "441" }
        return type
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
        throw InvalidSymbolSignal()
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
        message = "Calling 'kspRequireNotNull' with non-nullable value is redundant. " +
            "Use 'kspRequire' if checking a Boolean condition, " +
            "or remove this check if it is unnecessary.",
        level = DeprecationLevel.ERROR
    )
    private inline fun <T : Any> SymbolSource.kspRequireNotNull(value: T, crossinline message: () -> String): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Calling 'kspRequireNotNull' with nullable Boolean is ambiguous. " +
            "Use 'kspRequire' with an explicit condition instead.",
        replaceWith = ReplaceWith("kspRequire(value == true, message)"),
        level = DeprecationLevel.ERROR
    )
    private fun SymbolSource.kspRequireNotNull(value: Boolean?, message: () -> String): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    private fun <T, R> Iterable<T>.filterValid(block: (T) -> R): List<R> =
        mapNotNull {
            try {
                block(it)
            } catch (_: InvalidSymbolSignal) {
                null
            }
        }

    private class InvalidSymbolSignal : Exception()
}

// TODO: User-friendly errors
