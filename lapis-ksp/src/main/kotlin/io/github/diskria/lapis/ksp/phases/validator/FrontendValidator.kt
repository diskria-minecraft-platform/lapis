package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.ksp.KspLogger
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.extensions.isSubpackageOf
import io.github.diskria.lapis.ksp.phases.parser.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.FunctionParameter
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.Type
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import java.util.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class FrontendValidator(private val options: KspOptions, private val logger: KspLogger) {

    fun validatePatches(patches: List<ParsedPatch>): List<Patch> =
        patches.filterValid { it.validate() }

    private fun ParsedPatch.validate(): Patch {
        validateTypeParameters(typeParameters)
        kspRequire(isTopLevel) {
            """
            KMixin must be top-level.
            Why: Java Mixin requires a standalone type hierarchy and cannot depend on an enclosing scope.
            How to fix: Move the KMixin out of the enclosing scope.
            """.trimIndent()
        }
        kspRequire(hasPackageName) {
            """
            KMixin must be in a package.
            Why: Java Mixin in an isolated named package cannot access declarations in the default package.
            How to fix: Move the KMixin into a package.
            """.trimIndent()
        }
        kspRequire(isPublic) {
            """
            KMixin must be public.
            Why: Public visibility is required for Java Mixin binding.
            How to fix: Make the KMixin public.
            """.trimIndent()
        }
        kspRequire(!isSealed) { "" }
        kspRequire(!isOpen) { "" }
        val mixinAnnotations = annotations.normalize().filterValid { it.validate() }
        val targetArgument = kspRequireNotNull(annotations.findApiArgument(KMixin::target)) {
            val usedDesc = listOfNotNull(
                if (mixinAnnotations.isEmpty()) "generating the Java @Mixin annotation" else null,
                "subtype relationship checks",
            ).joinToString(" and ")
            """
            Target argument must be valid.
            Why: Target type is required for $usedDesc.
            How to fix: Ensure the target argument in @KMixin has no compilation errors.
            """.trimIndent()
        }
        val kMixinInitStrategy = kspRequireNotNull(annotations.findApiArgument(KMixin::initStrategy)?.value) {
            """
            Init strategy argument must be valid.
            Why: Init strategy is used for generating instantiation logic in Java Mixin. 
            How to fix: Ensure the initStrategy argument in @KMixin has no compilation errors.
            """.trimIndent()
        }
        val kMixinSide = kspRequireNotNull(annotations.findApiArgument(KMixin::side)?.value) {
            """
            Side argument must be valid and specified explicitly.
            Why: Implicit side risks loading the Java Mixin in the wrong target environment.
            How to fix: Specify the side argument explicitly in @KMixin and ensure it has no compilation errors.
            """.trimIndent()
        }
        val targetType = targetArgument.value.validate()
        val (injectionFunctions, regularFunctions) = functions.partition { function ->
            function.annotations.normalize().filterValid { it.validate() }.isNotEmpty()
        }
        val extensionProperties = properties.filter { it.annotations.hasApiAnnotation<Extension>() }
            .filterValid { it.validateAsExtension(targetType) }
        val extensionFunctions = regularFunctions.filter { it.annotations.hasApiAnnotation<Extension>() }
            .filterValid { it.validateAsExtension(targetType) }
        val kShadowProperties = properties.filter { it.annotations.hasApiAnnotation<KShadow>() }
            .filterValid { it.validateAsShadow(isInterface) }
        val kShadowFunctions = regularFunctions.filter { it.annotations.hasApiAnnotation<KShadow>() }
            .filterValid { it.validateAsShadow(isInterface) }
        val injections = injectionFunctions
            .filterValid { it.validateAsInjection(isInCompanionObject = false, targetType) }
        val companionObject = companionObject?.validate()?.let { companionObject ->
            val injections = companionObject.functions
                .filterValid { it.validateAsInjection(isInCompanionObject = true, targetType) }
            Patch.CompanionObject(name = companionObject.name, injections = injections)
        }
        val classKind = if (isClass) {
            val constructor = kspRequireNotNull(constructors.singleOrNull()) {
                """
                KMixin class must have exactly one constructor.
                Why: Java Mixin requires a single deterministic constructor for instantiation logic.
                How to fix: Keep exactly one constructor in the KMixin class.
                """.trimIndent()
            }
            constructor.kspRequire(constructor.isPublic) {
                """
                KMixin constructor must be public.
                Why: Java Mixin requires public access to instantiate KMixin.
                How to fix: Make the KMixin constructor public.
                """.trimIndent()
            }
            Patch.Class(isAbstract, constructor.parameters.filterValid { it.validate(targetType) })
        } else if (isInterface) {
            Patch.Interface
        } else {
            kspError {
                """
                KMixin must be a class or an interface.
                Why: Java Mixin can only be represented as a class or an interface.
                How to fix: Change the KMixin declaration to a class or an interface.
                """.trimIndent()
            }
        }
        return Patch(
            containingFile = node.containingFile,
            classDeclaration = classDeclaration,
            name = name,
            side = kMixinSide,
            initStrategy = kMixinInitStrategy,
            classKind = classKind,
            targetType = targetType,
            shadowSources = if (isAbstract || isInterface) kShadowProperties + kShadowFunctions else emptyList(),
            extensionSources = extensionProperties + extensionFunctions,
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = mixinAnnotations,
        )
    }

    private fun ParsedPatch.CompanionObject.validate(): ParsedPatch.CompanionObject {
        kspRequire(isPublic) { "" }
        return this
    }

    private fun ParsedPatch.Constructor.Parameter.validate(targetType: Type) = when {
        annotations.hasApiAnnotation<Origin>() -> Patch.Class.ConstructorParameter.Origin(
            name = name.validate(),
            type = validateTargetSubtype(type.validate(), targetType, "@Origin parameter"),
        )

        else -> kspError { "" }
    }

    private fun ParsedPatch.Property.validateAsExtension(targetType: Type): Patch.Extension.Property {
        kspRequireNotNull(getter) { "" }
        kspRequireNotNull(getter.jvmName) { "" }
        kspRequire(isPublic) { "" }
        kspRequire(!hasExtensionReceiver) { "" }
        kspRequire(!isOpen && !isAbstract) { "" }
        validateTypeParameters(typeParameters)
        return Patch.Extension.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "" } else null,
            type = type.validate(),
            receiverType = targetType,
        )
    }

    private fun ParsedPatch.Function.validateAsExtension(targetType: Type): Patch.Extension.Function {
        kspRequire(isPublic) { "" }
        kspRequireNotNull(jvmName) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        kspRequire(!isOpen && !isAbstract) { "" }
        validateTypeParameters(typeParameters)
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
            receiverType = targetType,
        )
    }

    private fun ParsedPatch.Property.validateAsShadow(isInterface: Boolean): Patch.Shadow.Property {
        kspRequire(isPublic) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(!hasExtensionReceiver) { "" }
        kspRequireNotNull(getter) { "" }
        kspRequireNotNull(getter.jvmName) { "" }
        validateTypeParameters(typeParameters)
        val mappingNameValueArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(name, "@KShadow property name", sources = mappingNameValueArgument == null)
        val mappingName = mappingNameValueArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow property's @MappingName value", sources = true)
        } ?: name
        val kShadowModifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        return Patch.Shadow.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "" } else null,
            mappingName = mappingName,
            modifiers = validateModifiers(kShadowModifiersArgument?.elements.orEmpty(), isInterface, isField = true),
            type = type.validate(),
            mixinAnnotations = getter.annotations.normalize().filterValid { it.validate() },
        )
    }

    private fun ParsedPatch.Function.validateAsShadow(isInterface: Boolean): Patch.Shadow.Function {
        kspRequire(isPublic) { "" }
        kspRequireNotNull(jvmName) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        validateTypeParameters(typeParameters)
        val mappingNameArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(name, "@KShadow function name", sources = mappingNameArgument == null)
        val mappingName = mappingNameArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow function's @MappingName value", sources = true)
        } ?: name
        val kShadowModifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        return Patch.Shadow.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters.map { FunctionParameter(name = it.name.validate(), type = it.type.validate()) },
            returnType = returnType.validate(),
            mappingName = mappingName,
            mixinAnnotations = annotations.normalize().filterValid { it.validate() },
            modifiers = validateModifiers(kShadowModifiersArgument?.elements.orEmpty(), isInterface, isField = false),
        )
    }

    private fun ParsedPatch.Function.validateAsInjection(
        isInCompanionObject: Boolean,
        targetType: Type,
    ): Patch.Injection {
        kspRequireNotNull(jvmName) { "" }
        kspRequire(!isOpen) { "" }
        if (isInCompanionObject) {
            kspRequire(extensionReceiverType == null) { "" }
        }
        validateTypeParameters(typeParameters)
        return Patch.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType?.let {
                validateTargetSubtype(it.validate(), targetType, "Injection extension receiver")
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

    context(node: KspNode)
    private fun ParsedName.validate(): String {
        node.kspRequire(this is ValidName) { "" }
        return name
    }

    private fun ParsedType.validate(): Type {
        kspRequire(this is ValidType) { "" }
        return Type(type = type, isAny = isAny, isUnit = isUnit, isInterface = isInterface)
    }

    private fun KspNode.validateClassDeclaration(classDeclaration: KSClassDeclaration?): KSClassDeclaration {
        kspRequire(classDeclaration?.validate(enableNewFeatures = true) == true) { "" }
        return classDeclaration
    }

    private fun KspNode.validateModifiers(
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
        kspRequire(allowed.containsAll(result)) { "" }
        kspRequire(result.count { it in JavaModifiers.VISIBILITIES } <= 1) { "" }
        if (isField) {
            if (FINAL in result) {
                kspRequire(VOLATILE !in result) { "" }
            }
        } else {
            if (ABSTRACT in result) {
                kspRequire(result.none { it in JavaModifiers.ABSTRACT_ILLEGALS }) { "" }
            }
            if (NATIVE in result) {
                kspRequire(DEFAULT !in result) { "" }
            }
            if (isInterface) {
                if (PRIVATE in result) {
                    kspRequire(DEFAULT !in result) { "" }
                    kspRequire(ABSTRACT !in result) { "" }
                } else {
                    kspRequire(result.count { it in EnumSet.of(ABSTRACT, STATIC, DEFAULT) } == 1) { "" }
                }
            } else {
                kspRequire(DEFAULT !in result) { "" }
            }
        }
        return result
    }

    private fun KspNode.validateJavaIdentifierName(name: String, roleDesc: String, sources: Boolean = false): String {
        kspRequire(SourceVersion.isName(name)) {
            val ensureDesc = if (sources) {
                "matches a valid name in the decompiled Minecraft source code"
            } else {
                "is a valid Java identifier (e.g. starts with a letter and contains no spaces or special characters)"
            }
            """
            $roleDesc '$name' must be a valid Java identifier.
            Why: This name is used to generate method signatures in Java Mixin.
            How to fix: Ensure the name $ensureDesc.
            """.trimIndent()
        }
        return name
    }

    private fun ParsedAnnotations.normalize() = external.filter { annotation ->
        if (annotation !is ValidAnnotation) return@filter true
        options.mixinAnnotationPackages.any { annotation.type.packageName.isSubpackageOf(it) }
    }

    private fun ParsedAnnotation.validate(): MixinAnnotation {
        kspRequire(this is ValidAnnotation) {
            """
            Annotations must be valid here.
            Why: Package name is required to determine whether the annotation should be copied into Java Mixin.
            How to fix: Ensure the annotation is correctly imported and has no compilation errors.
            """.trimIndent()
        }
        return MixinAnnotation(
            typeClassDeclaration = validateClassDeclaration(type.classDeclaration),
            arguments = arguments.normalize().filterValid { it.validate() },
        )
    }

    private fun List<ParsedAnnotation.Argument>.normalize() =
        filter { it !is ParsedAnnotation.ValidArgument || it.isExplicit }

    private fun ParsedAnnotation.Argument.validate(): MixinAnnotation.Argument {
        kspRequire(this is ParsedAnnotation.ValidArgument) {
            """
            Mixin-related annotation arguments must be valid.
            Why: Invalid arguments cannot be mapped into Java Mixin.
            How to fix: Ensure the annotation argument has no compilation errors.
            """.trimIndent()
        }
        return when (this) {
            is ParsedAnnotation.ScalarArgument -> MixinAnnotation.ScalarArgument(name, value.validate())
            is ParsedAnnotation.ArrayArgument -> {
                MixinAnnotation.ArrayArgument(name, elements.filterValid { it.validate() })
            }
        }
    }

    context(node: KspNode)
    private fun ParsedAnnotation.Argument.Value<*>.validate() = when (this) {
        is ParsedAnnotation.Argument.BooleanValue -> MixinAnnotation.Argument.BooleanValue(raw)
        is ParsedAnnotation.Argument.ByteValue -> MixinAnnotation.Argument.ByteValue(raw)
        is ParsedAnnotation.Argument.ShortValue -> MixinAnnotation.Argument.ShortValue(raw)
        is ParsedAnnotation.Argument.IntValue -> MixinAnnotation.Argument.IntValue(raw)
        is ParsedAnnotation.Argument.LongValue -> MixinAnnotation.Argument.LongValue(raw)
        is ParsedAnnotation.Argument.CharValue -> MixinAnnotation.Argument.CharValue(raw)
        is ParsedAnnotation.Argument.FloatValue -> MixinAnnotation.Argument.FloatValue(raw)
        is ParsedAnnotation.Argument.DoubleValue -> MixinAnnotation.Argument.DoubleValue(raw)
        is ParsedAnnotation.Argument.StringValue -> MixinAnnotation.Argument.StringValue(raw)
        is ParsedAnnotation.Argument.TypeValue -> MixinAnnotation.Argument.TypeValue(raw.validate())
        is ParsedAnnotation.Argument.EnumValue -> MixinAnnotation.Argument.EnumValue(
            node.validateClassDeclaration(enumClassDeclaration), entryName
        )

        is ParsedAnnotation.Argument.AnnotationValue -> MixinAnnotation.Argument.AnnotationValue(raw.validate())
    }

    private fun KspNode.validateTargetSubtype(candidate: Type, target: Type, roleDesc: String): Type {
        kspRequire(candidate.isSubtypeOf(target)) {
            val typeName = candidate.type.toString()
            val targetTypeName = target.type.toString()
            """
            $roleDesc type '$typeName' must be a subtype of target type '$targetTypeName'.
            Why: In Java Mixin, 'this' is the target type, so an unsafe cast requires a subtype relationship.
            How to fix: Ensure type is a subtype of target type.
            """.trimIndent()
        }
        kspRequire(!candidate.type.isMarkedNullable) {
            val typeName = candidate.type.toString()
            """
            $roleDesc type '$typeName' cannot be nullable.
            Why: In Java Mixin, an instance of the target type is always initialized and is guaranteed to be non-null.
            How to fix: Remove the nullable mark ('?') from type.
            """.trimIndent()
        }
        return candidate
    }

    private fun KspNode.validateTypeParameters(typeParameters: List<ParsedTypeParameter>) {
        kspRequire(typeParameters.isEmpty()) { "Type parameters is not supported yet." }
    }

    private inline fun KspNode.kspError(crossinline message: () -> String): Nothing {
        logger.error(message(), node)
        throw InvalidSymbolSignal()
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun KspNode.kspRequire(condition: Boolean, crossinline message: () -> String) {
        contract { returns() implies condition }
        if (!condition) {
            kspError(message = message)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <T> KspNode.kspRequireNotNull(value: T?, crossinline message: () -> String): T {
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
    private inline fun <T : Any> KspNode.kspRequireNotNull(value: T, crossinline message: () -> String): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Calling 'kspRequireNotNull' with nullable Boolean is ambiguous. " +
            "Use 'kspRequire' with an explicit condition instead.",
        replaceWith = ReplaceWith("kspRequire(value == true, message)"),
        level = DeprecationLevel.ERROR
    )
    private fun KspNode.kspRequireNotNull(value: Boolean?, message: () -> String): Nothing {
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
