package io.github.diskria.lapis.ksp.phases.validator

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.Variance
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.ksp.KspLogger
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.extensions.isSubpackageOf
import io.github.diskria.lapis.ksp.phases.parser.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.*
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
        val mixinAnnotations = annotations.filterMixinAnnotations()
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
        val initStrategy = kspRequireNotNull(annotations.findApiArgument(KMixin::initStrategy)?.value) {
            """
            Init strategy argument must be valid.
            Why: Init strategy is used for generating instantiation logic in Java Mixin. 
            How to fix: Ensure the initStrategy argument in @KMixin has no compilation errors.
            """.trimIndent()
        }
        val side = kspRequireNotNull(annotations.findApiArgument(KMixin::side)?.value) {
            """
            Side argument must be valid and specified explicitly.
            Why: Implicit side risks loading the Java Mixin in the wrong target environment.
            How to fix: Specify the side argument explicitly in @KMixin and ensure it has no compilation errors.
            """.trimIndent()
        }
        val targetType = targetArgument.value.validate()
        val targetClassDeclaration = kspRequireNotNull(targetType.classDeclaration) { "" }

        val shadowProperties = mutableListOf<Patch.Shadow.Property>()
        val extensionProperties = mutableListOf<Patch.Extension.Property>()
        properties.filterValid { property ->
            val getterMixinAnnotations = property.getter?.annotations?.filterMixinAnnotations().orEmpty()
            val hasShadowAnnotation = property.annotations.hasApiAnnotation<KShadow>()
            val hasExtensionAnnotation = property.annotations.hasApiAnnotation<Extension>()
            if (hasShadowAnnotation && hasExtensionAnnotation) {
                property.kspError {
                    """
                    Property cannot be marked as both @KShadow and @Extension.
                    Why: @KShadow targets existing target members, while @Extension introduces new synthetic members.
                    How to fix: Remove either @KShadow or @Extension annotation from the property.
                    """.trimIndent()
                }
            }
            if (hasShadowAnnotation) {
                shadowProperties += property.validateAsShadow(isInterface, getterMixinAnnotations)
            } else if (hasExtensionAnnotation) {
                property.kspRequire(getterMixinAnnotations.isEmpty()) {
                    """
                    Extension properties cannot have mixin-related annotations on their getter.
                    Why: Extension members introduce new functionality and cannot be used as injection points.
                    How to fix: Remove mixin annotations (such as @Inject) from the property getter.
                    """.trimIndent()
                }
                extensionProperties += property.validateAsExtension(targetType)
            }
        }

        val shadowFunctions = mutableListOf<Patch.Shadow.Function>()
        val extensionFunctions = mutableListOf<Patch.Extension.Function>()
        val injections = mutableListOf<Patch.Injection>()
        functions.filterValid { function ->
            val mixinAnnotations = function.annotations.filterMixinAnnotations()
            val hasShadowAnnotation = function.annotations.hasApiAnnotation<KShadow>()
            val hasExtensionAnnotation = function.annotations.hasApiAnnotation<Extension>()
            if (hasShadowAnnotation && hasExtensionAnnotation) {
                function.kspError {
                    """
                    Function cannot be marked as both @KShadow and @Extension.
                    Why: @KShadow targets existing target members, while @Extension introduces new synthetic members.
                    How to fix: Remove either @KShadow or @Extension annotation from the function.
                    """.trimIndent()
                }
            }
            if (hasShadowAnnotation) {
                shadowFunctions += function.validateAsShadow(isInterface, mixinAnnotations)
            } else if (hasExtensionAnnotation) {
                function.kspRequire(mixinAnnotations.isEmpty()) {
                    """
                    Extension functions cannot have mixin-related annotations.
                    Why: Extension members introduce new functionality and cannot be used as injection points.
                    How to fix: Remove mixin annotations (such as @Inject) from the function.
                    """.trimIndent()
                }
                extensionFunctions += function.validateAsExtension(targetType)
            } else if (mixinAnnotations.isNotEmpty()) {
                injections += function.validateAsInjection(isInCompanionObject = false, targetType, mixinAnnotations)
            }
        }
        val companionObject = companionObject?.let { companionObject ->
            val injections = mutableListOf<Patch.Injection>()
            companionObject.functions.filterValid { function ->
                function.kspRequire(!function.annotations.hasApiAnnotation<KShadow>()) {
                    """
                    @KShadow functions in companion objects are currently unsupported.
                    Why: Static shadowing requires generating accessor interfaces, which is planned for a future release.
                    How to fix: Remove @KShadow from the companion object function for now or access the target member via reflection until static shadowing is implemented.
                    """.trimIndent()
                }
                function.kspRequire(!function.annotations.hasApiAnnotation<Extension>()) {
                    """
                    @Extension functions in companion objects are unnecessary and unsupported.
                    Why: Companion object functions are already globally accessible static members. Extensions are meant for instance-bound members of the target class.
                    How to fix: Remove @Extension annotation and call the companion object function directly.
                    """.trimIndent()
                }
                val mixinAnnotations = function.annotations.filterMixinAnnotations()
                if (mixinAnnotations.isNotEmpty()) {
                    injections += function.validateAsInjection(isInCompanionObject = true, targetType, mixinAnnotations)
                }
            }
            if (injections.isNotEmpty()) {
                companionObject.kspRequire(companionObject.isPublic) {
                    """
                    Companion object containing mixin injections must be public.
                    Why: Target mixin injections in companion objects must be accessible by Java Mixin.
                    How to fix: Make the companion object public.
                    """.trimIndent()
                }
            }
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
            Patch.Class(
                isAbstract = isAbstract,
                constructorParameters = constructor.parameters.filterValid { it.validate(targetType) },
            )
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
            side = side,
            initStrategy = initStrategy,
            classKind = classKind,
            targetClassDeclaration = targetClassDeclaration,
            shadowSources = if (isAbstract || isInterface) shadowProperties + shadowFunctions else emptyList(),
            extensionSources = extensionProperties + extensionFunctions,
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = mixinAnnotations,
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Constructor.Parameter.validate(targetType: Type) = when {
        annotations.hasApiAnnotation<Origin>() -> Patch.Class.ConstructorParameter.Origin(
            name = name,
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
        return Patch.Extension.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "" } else null,
            type = type.validate(),
            receiverType = targetType,
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Function.validateAsExtension(targetType: Type): Patch.Extension.Function {
        kspRequire(isPublic) { "" }
        kspRequireNotNull(jvmName) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        kspRequire(!isOpen && !isAbstract) { "" }
        val parameters = parameters.map {
            FunctionParameter(
                name = it.name,
                type = it.type.validate(),
            )
        }
        return Patch.Extension.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType.validate(),
            receiverType = targetType,
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Property.validateAsShadow(
        isInterface: Boolean,
        mixinAnnotations: List<MixinAnnotation>,
    ): Patch.Shadow.Property {
        kspRequire(isPublic) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(!hasExtensionReceiver) { "" }
        kspRequireNotNull(getter) { "" }
        kspRequireNotNull(getter.jvmName) { "" }
        val mappingNameValueArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(name, "@KShadow property name", sources = mappingNameValueArgument == null)
        val mappingName = mappingNameValueArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow property's @MappingName value", sources = true)
        } ?: name
        val modifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        return Patch.Shadow.Property(
            name = name,
            getterJvmName = getter.jvmName,
            setterJvmName = if (setter != null) kspRequireNotNull(setter.jvmName) { "" } else null,
            mappingName = mappingName,
            modifiers = validateShadowModifiers(modifiersArgument?.elements.orEmpty(), isInterface, isProperty = true),
            type = type.validate(),
            mixinAnnotations = mixinAnnotations,
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Function.validateAsShadow(
        isInterface: Boolean,
        mixinAnnotations: List<MixinAnnotation>,
    ): Patch.Shadow.Function {
        kspRequire(isPublic) { "" }
        kspRequireNotNull(jvmName) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        val mappingNameArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(name, "@KShadow function name", sources = mappingNameArgument == null)
        val mappingName = mappingNameArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow function's @MappingName value", sources = true)
        } ?: name
        val modifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        return Patch.Shadow.Function(
            name = name,
            jvmName = jvmName,
            parameters = parameters.map { FunctionParameter(name = it.name, type = it.type.validate()) },
            returnType = returnType.validate(),
            mappingName = mappingName,
            mixinAnnotations = mixinAnnotations,
            modifiers = validateShadowModifiers(modifiersArgument?.elements.orEmpty(), isInterface, isProperty = false),
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Function.validateAsInjection(
        isInCompanionObject: Boolean,
        targetType: Type,
        mixinAnnotations: List<MixinAnnotation>,
    ): Patch.Injection {
        kspRequireNotNull(jvmName) { "" }
        kspRequire(!isOpen) { "" }
        if (isInCompanionObject) {
            kspRequire(extensionReceiverType == null) { "" }
        }
        return Patch.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType?.let {
                validateTargetSubtype(
                    candidate = it.validate(),
                    target = targetType,
                    roleDesc = "Injection extension receiver",
                )
            },
            mixinAnnotations = mixinAnnotations,
            parameters = parameters.map { it.validateAsInjectionParameter() },
            returnType = returnType.validate(),
            typeParameters = typeParameters.validate(),
        )
    }

    private fun ParsedPatch.Function.Parameter.validateAsInjectionParameter() = Patch.Injection.Parameter(
        name = name,
        type = type.validate(),
        mixinAnnotations = annotations.filterMixinAnnotations(),
    )

    private fun ParsedType.validate(): Type {
        kspRequire(this is ValidType) { "" }
        return Type(
            ksType = type,
            isAny = isAny,
            isUnit = isUnit,
            isInterface = isInterface,
            classDeclaration = classDeclaration,
        )
    }

    private fun NodeHolder.validateShadowModifiers(
        rawModifiers: List<Modifier>,
        isInterface: Boolean,
        isProperty: Boolean,
    ): EnumSet<Modifier> {
        val result = if (rawModifiers.isEmpty()) EnumSet.noneOf(Modifier::class.java) else EnumSet.copyOf(rawModifiers)

        if (isInterface) {
            if (PRIVATE !in result && PROTECTED !in result) {
                result.add(PUBLIC)
            }
            if (isProperty) {
                result.add(STATIC)
                result.add(FINAL)
            } else if (DEFAULT !in result && STATIC !in result && PRIVATE !in result) {
                result.add(ABSTRACT)
            }
        }

        kspRequire(STATIC !in rawModifiers) {
            """
            Static @KShadow members must be declared in the companion object instead of using the STATIC modifier.
            Why: Java Mixin targets static members through companion object declarations to preserve Kotlin scoping.
            How to fix: Remove 'STATIC' modifier and declare the @KShadow member inside the companion object.
            """.trimIndent()
        }

        fun requireModifiers(condition: Boolean, problem: () -> String) {
            kspRequire(condition) {
                val javaMemberName = if (isProperty) "field" else "method"
                val kotlinMemberName = if (isProperty) "property" else "function"
                val containerName = if (isInterface) "interface" else "class"
                """
                @KShadow $kotlinMemberName representing Java $javaMemberName ${problem()}.
                Why: Target bytecode cannot have this modifier combination.
                How to fix: Check the original $javaMemberName in Minecraft $containerName source code and copy its exact modifiers.
                """.trimIndent()
            }
        }

        fun Iterable<Modifier>.joinToUppercase(): String = joinToString { it.name }

        val allowedModifiers = if (isProperty) JavaModifiers.FIELD_ALLOWED else JavaModifiers.METHOD_ALLOWED
        val invalidModifiers = result.filter { it !in allowedModifiers }
        requireModifiers(invalidModifiers.isEmpty()) {
            "has invalid modifiers: ${invalidModifiers.joinToUppercase()}"
        }
        val visibilities = result.filter { it in JavaModifiers.VISIBILITIES }
        requireModifiers(visibilities.size <= 1) {
            "has multiple visibility modifiers: ${visibilities.joinToUppercase()}"
        }
        if (isProperty) {
            if (FINAL in result) {
                requireModifiers(VOLATILE !in result) { "cannot be both FINAL and VOLATILE" }
            }
        } else {
            if (ABSTRACT in result) {
                val illegalAbstractModifiers = result.filter { it in JavaModifiers.ABSTRACT_ILLEGALS }
                requireModifiers(illegalAbstractModifiers.isEmpty()) {
                    "has illegal modifiers for an abstract declaration: ${illegalAbstractModifiers.joinToUppercase()}"
                }
            }
            if (NATIVE in result) {
                requireModifiers(DEFAULT !in result) { "cannot be both NATIVE and DEFAULT" }
            }
            if (isInterface) {
                if (PRIVATE in result) {
                    requireModifiers(DEFAULT !in result) { "cannot be both PRIVATE and DEFAULT in interface" }
                    requireModifiers(ABSTRACT !in result) { "cannot be both PRIVATE and ABSTRACT in interface" }
                } else {
                    val executionTypes = result.filter { it in JavaModifiers.EXECUTION_TYPES }
                    requireModifiers(executionTypes.size == 1) {
                        val types = JavaModifiers.EXECUTION_TYPES.joinToUppercase()
                        "must specify exactly one execution type ($types) in interface, " +
                            "found: ${executionTypes.joinToUppercase()}"
                    }
                }
            } else {
                requireModifiers(DEFAULT !in result) { "cannot use DEFAULT modifier outside of interface" }
            }
        }
        return result
    }

    private fun NodeHolder.validateJavaIdentifierName(
        name: String,
        roleDesc: String,
        sources: Boolean = false,
    ): String {
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

    private fun ParsedAnnotations.filterMixinAnnotations() =
        external.filter { annotation ->
            if (annotation !is ValidAnnotation) return@filter true
            options.mixinAnnotationPackages.any { annotation.type.packageName?.isSubpackageOf(it) == true }
        }.filterValid(atomic = true) { it.validate() }

    private fun ParsedAnnotation.validate(): MixinAnnotation {
        kspRequire(this is ValidAnnotation) {
            """
            Annotations must be valid here.
            Why: Package name is required to determine whether the annotation should be copied into Java Mixin.
            How to fix: Ensure the annotation is correctly imported and has no compilation errors.
            """.trimIndent()
        }
        kspRequireNotNull(type.classDeclaration) { "" }
        return MixinAnnotation(
            typeClassDeclaration = type.classDeclaration,
            arguments = arguments.validate(),
        )
    }

    @JvmName("validateAnnotationArguments")
    private fun List<ParsedAnnotation.Argument>.validate(): List<MixinAnnotation.Argument> =
        filter { it !is ParsedAnnotation.ValidArgument || it.isExplicit }.filterValid(atomic = true) { it.validate() }

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
            is ParsedAnnotation.ArrayArgument -> MixinAnnotation.ArrayArgument(name, elements.validate())
        }
    }

    @JvmName("validateAnnotationArgumentValues")
    private fun List<ParsedAnnotation.Argument.Value>.validate(): List<MixinAnnotation.Argument.Value> =
        filterValid { it.validate() }

    private fun ParsedAnnotation.Argument.Value.validate() = when (this) {
        is ParsedAnnotation.Argument.BooleanValue -> MixinAnnotation.Argument.BooleanValue(boolean)
        is ParsedAnnotation.Argument.ByteValue -> MixinAnnotation.Argument.ByteValue(byte)
        is ParsedAnnotation.Argument.ShortValue -> MixinAnnotation.Argument.ShortValue(short)
        is ParsedAnnotation.Argument.IntValue -> MixinAnnotation.Argument.IntValue(int)
        is ParsedAnnotation.Argument.LongValue -> MixinAnnotation.Argument.LongValue(long)
        is ParsedAnnotation.Argument.CharValue -> MixinAnnotation.Argument.CharValue(char)
        is ParsedAnnotation.Argument.FloatValue -> MixinAnnotation.Argument.FloatValue(float)
        is ParsedAnnotation.Argument.DoubleValue -> MixinAnnotation.Argument.DoubleValue(double)
        is ParsedAnnotation.Argument.StringValue -> MixinAnnotation.Argument.StringValue(string)
        is ParsedAnnotation.Argument.TypeValue -> {
            val validType = type.validate()
            type.kspRequire(validType.ksType.arguments.all { it.variance == Variance.STAR }) {
                val typeName = validType.ksType.toString()
                """
                Generic type arguments in class reference '$typeName' are not supported.
                Why: Generic type arguments cannot be mapped to Java Mixin annotations, as Java only supports raw class references.
                How to fix: Remove type arguments from the class reference.
                """.trimIndent()
            }
            val classDeclaration = type.kspRequireNotNull(validType.classDeclaration) {
                val typeName = validType.ksType.toString()
                """
                Class reference argument '$typeName' must resolve to a valid class declaration.
                Why: The specified type could not be resolved by KSP.
                How to fix: Ensure the class argument has no compilation errors.
                """.trimIndent()
            }
            MixinAnnotation.Argument.ClassValue(classDeclaration)
        }

        is ParsedAnnotation.Argument.EnumValue -> MixinAnnotation.Argument.EnumValue(enumClassDeclaration, entryName)
        is ParsedAnnotation.Argument.AnnotationValue -> MixinAnnotation.Argument.AnnotationValue(annotation.validate())
    }

    private fun NodeHolder.validateTargetSubtype(candidate: Type, target: Type, roleDesc: String): Type {
        val candidateErased = candidate.ksType.starProjection()
        kspRequire(!candidateErased.isMarkedNullable) {
            val typeName = candidateErased.toString()
            """
            $roleDesc type '$typeName' cannot be nullable.
            Why: In Java Mixin, an instance of the target type is always initialized and is guaranteed to be non-null.
            How to fix: Remove the nullable mark ('?') from type.
            """.trimIndent()
        }
        val targetErased = target.ksType.starProjection()
        kspRequire(targetErased.isAssignableFrom(candidateErased)) {
            val typeName = candidateErased.toString()
            val targetTypeName = targetErased.toString()
            """
            $roleDesc type '$typeName' must be a subtype of target type '$targetTypeName'.
            Why: In Java Mixin, 'this' is the target type, so an unsafe cast requires a subtype relationship.
            How to fix: Ensure type is a subtype of target type.
            """.trimIndent()
        }
        return candidate
    }

    private fun List<ParsedTypeParameter>.validate() = filterValid(atomic = true) { it.validate() }

    private fun ParsedTypeParameter.validate(): TypeParameter {
        kspRequire(variance == Variance.INVARIANT) {
            """
            Type parameter '$name' cannot have variance modifier '${variance.label}'.
            Why: Java type parameters are strictly invariant and do not support 'in' or 'out' modifiers.
            How to fix: Remove variance modifier from type parameter.
            """.trimIndent()
        }
        kspRequire(!isReified) {
            """
            Reified type parameter '$name' is not supported in @KMixin.
            Why: 'reified' type parameters only exist in Kotlin inline functions and cannot be used in Java.
            How to fix: Remove the 'reified' keyword from parameter.
            """.trimIndent()
        }
        return TypeParameter(
            name = name,
            bounds = bounds.filterValid { it.validate() },
            ksTypeParameter = node,
        )
    }

    private inline fun NodeHolder.kspError(crossinline message: () -> String): Nothing {
        logger.error(message(), node)
        throw InvalidSymbolSignal()
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun NodeHolder.kspRequire(condition: Boolean, crossinline message: () -> String) {
        contract { returns() implies condition }
        if (!condition) {
            kspError(message = message)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <T> NodeHolder.kspRequireNotNull(value: T?, crossinline message: () -> String): T {
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
    private inline fun <T : Any> NodeHolder.kspRequireNotNull(value: T, crossinline message: () -> String): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Calling 'kspRequireNotNull' with nullable Boolean is ambiguous. " +
            "Use 'kspRequire' with an explicit condition instead.",
        replaceWith = ReplaceWith("kspRequire(value == true, message)"),
        level = DeprecationLevel.ERROR
    )
    private fun NodeHolder.kspRequireNotNull(value: Boolean?, message: () -> String): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    private inline fun <T, R : Any> Iterable<T>.filterValid(atomic: Boolean = false, block: (T) -> R): List<R> {
        var hasError = false
        val result = mapNotNull {
            try {
                block(it)
            } catch (_: InvalidSymbolSignal) {
                hasError = true
                null
            }
        }
        if (atomic && hasError) {
            throw InvalidSymbolSignal()
        }
        return result
    }

    private class InvalidSymbolSignal : Exception()
}

// TODO: User-friendly errors
