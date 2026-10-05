package io.github.diskria.lapis.core.validator

import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.core.extensions.isSubpackageOf
import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.JavaModifiers
import io.github.diskria.lapis.core.validator.models.*
import java.util.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class CoreValidator<O>(private val nodes: Sequence<KMixinNode<O>>, private val options: CoreOptions) {

    fun validate(): Sequence<KMixinModel<O>> =
        nodes.mapValid { it.validate() }

    private fun KMixinNode<O>.validate(): KMixinModel<O> {
        kspRequire(isTopLevel) {
            """
            KMixin must be top-level.
            Why: Java Mixin requires a standalone type hierarchy and cannot depend on an enclosing scope.
            How to fix: Move the KMixin out of the enclosing scope.
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
        val validTargetTypeNode = targetArgument.value.validate()
        val targetTypeModel = kspRequireNotNull(validTargetTypeNode.toModel() as? ClassTypeModel) {
            ""
        }
        val typeParameters = typeParameters.validate(enclosing = emptyList())
        val shadowProperties = mutableListOf<KMixinModel.Shadow.Property>()
        val extensionProperties = mutableListOf<KMixinModel.Extension.Property>()
        properties.mapValid { property ->
            val getterMixinAnnotations = property.getter?.annotations?.filterMixinAnnotations()?.map { it.toModel() }
                .orEmpty()
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
                shadowProperties += property.toShadowModel(isInterface, getterMixinAnnotations, typeParameters)
            } else if (hasExtensionAnnotation) {
                property.kspRequire(getterMixinAnnotations.isEmpty()) {
                    """
                    Extension properties cannot have mixin-related annotations on their getter.
                    Why: Extension members introduce new functionality and cannot be used as injection points.
                    How to fix: Remove mixin annotations (such as @Inject) from the property getter.
                    """.trimIndent()
                }
                extensionProperties += property.toExtensionModel(typeParameters)
            }
        }
        val shadowFunctions = mutableListOf<KMixinModel.Shadow.Function>()
        val extensionFunctions = mutableListOf<KMixinModel.Extension.Function>()
        val injections = mutableListOf<KMixinModel.Injection>()
        functions.mapValid { function ->
            val mixinAnnotations = function.annotations.filterMixinAnnotations().map { it.toModel() }
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
                shadowFunctions += function.toShadowModel(isInterface, mixinAnnotations, typeParameters)
            } else if (hasExtensionAnnotation) {
                function.kspRequire(mixinAnnotations.isEmpty()) {
                    """
                    Extension functions cannot have mixin-related annotations.
                    Why: Extension members introduce new functionality and cannot be used as injection points.
                    How to fix: Remove mixin annotations (such as @Inject) from the function.
                    """.trimIndent()
                }
                extensionFunctions += function.toExtensionModel(typeParameters)
            } else if (mixinAnnotations.isNotEmpty()) {
                injections += function.validateAsInjection(
                    isInCompanionObject = false,
                    validTargetTypeNode,
                    mixinAnnotations,
                    typeParameters,
                )
            }
        }
        val companionObject = companionObject?.let { companionObject ->
            val injections = mutableListOf<KMixinModel.Injection>()
            companionObject.functions.mapValid { function ->
                function.kspRequire(!function.annotations.hasApiAnnotation<KShadow>()) {
                    TODO("@KShadow functions in companion objects are not implemented yet.")
                }
                function.kspRequire(!function.annotations.hasApiAnnotation<Extension>()) {
                    """
                    @Extension functions in companion objects are unnecessary and unsupported.
                    Why: Companion object functions are already globally accessible static members.
                    How to fix: Remove @Extension annotation and call the companion object function directly.
                    """.trimIndent()
                }
                val mixinAnnotations = function.annotations.filterMixinAnnotations().map { it.toModel() }
                if (mixinAnnotations.isNotEmpty()) {
                    injections += function.validateAsInjection(
                        isInCompanionObject = true,
                        validTargetTypeNode,
                        mixinAnnotations,
                        typeParameters,
                    )
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
            KMixinModel.CompanionObject(name = companionObject.name.validate().toModel(), injections = injections)
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
            KMixinModel.Class(
                isAbstract = isAbstract,
                constructorParameters = constructor.parameters.mapValid {
                    it.toModel(validTargetTypeNode, typeParameters)
                },
            )
        } else if (isInterface) {
            KMixinModel.Interface
        } else {
            kspError {
                """
                KMixin must be a class or an interface.
                Why: Java Mixin can only be represented as a class or an interface.
                How to fix: Change the KMixin declaration to a class or an interface.
                """.trimIndent()
            }
        }
        val validTypeNode = type.validate()
        val classTypeModel = kspRequireNotNull(validTypeNode.toModel() as? ClassTypeModel) { "" }
        return KMixinModel(
            origin = origin,
            type = classTypeModel,
            name = name.validate().toModel(),
            side = side,
            initStrategy = initStrategy,
            classKind = classKind,
            targetType = targetTypeModel,
            shadowSources = if (isAbstract || isInterface) shadowProperties + shadowFunctions else emptyList(),
            extensionSources = extensionProperties + extensionFunctions,
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = mixinAnnotations.map { it.toModel() },
            typeParameters = typeParameters,
        )
    }

    private fun KMixinNode.Constructor.Parameter.toModel(
        targetType: ValidTypeNode,
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = when {
        annotations.hasApiAnnotation<Origin>() -> KMixinModel.Class.ConstructorParameter.Origin(
            name = name.validate().toModel(),
            type = type.validate().requireSubtypeOf(targetType, "@Origin parameter").toModel(enclosingTypeParameters),
        )

        else -> kspError { "" }
    }

    private fun KMixinNode.Property.toExtensionModel(
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Extension.Property {
        val getter = kspRequireNotNull(getter) { "" }
        val getterJvmName = kspRequireNotNull(getter.jvmName) { "" }
        val setterJvmName = setter?.let {
            kspRequireNotNull(it.jvmName) { "" }
        }
        kspRequire(isPublic) { "" }
        kspRequire(!hasExtensionReceiver) { "" }
        kspRequire(!isOpen && !isAbstract) { "" }
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        return KMixinModel.Extension.Property(
            declaredName = name.validate().toModel(),
            getterJvmName = getterJvmName,
            setterJvmName = setterJvmName,
            type = type.validate().toModel(scopeTypeParameters),
            typeParameters = localTypeParameters,
            contextParameters = contextParameters.mapValid { it.validate(scopeTypeParameters) }
        )
    }

    private fun KMixinNode.Function.toExtensionModel(
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Extension.Function {
        kspRequire(isPublic) { "" }
        val jvmName = kspRequireNotNull(jvmName) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        kspRequire(!isOpen && !isAbstract) { "" }
        kspRequire(!isSuspending) { "Suspend extensions are not supported yet." }
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        val parameters = parameters.map {
            FunctionParameterModel(
                name = it.name.validate().toModel(),
                type = it.type.validate().toModel(scopeTypeParameters),
            )
        }
        return KMixinModel.Extension.Function(
            declaredName = name.validate().toModel(),
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType?.validate()?.toModel(scopeTypeParameters),
            typeParameters = localTypeParameters,
            contextParameters = contextParameters.mapValid { it.validate(scopeTypeParameters) }
        )
    }

    private fun KMixinNode.Property.toShadowModel(
        isInterface: Boolean,
        mixinAnnotations: List<MixinAnnotationModel>,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Shadow.Property {
        kspRequire(isPublic) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(!hasExtensionReceiver) { "" }
        val getter = kspRequireNotNull(getter) { "" }
        val getterJvmName = kspRequireNotNull(getter.jvmName) { "" }
        val setterJvmName = setter?.let {
            kspRequireNotNull(it.jvmName) { "" }
        }
        kspRequire(contextParameters.isEmpty()) { "" }
        val declaredName = name.validate().toModel()
        val mappingNameArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(declaredName, "@KShadow property name", mappingNameArgument == null)
        val mappingName = mappingNameArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow property's @MappingName value", sources = true)
        } ?: declaredName
        val modifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        kspRequire(typeParameters.isEmpty()) { "" }
        return KMixinModel.Shadow.Property(
            declaredName = name.validate().toModel(),
            getterJvmName = getterJvmName,
            setterJvmName = setterJvmName,
            mappingName = mappingName,
            modifiers = validateShadowModifiers(modifiersArgument?.elements.orEmpty(), isInterface, isProperty = true),
            type = type.validate().toModel(enclosingTypeParameters),
            mixinAnnotations = mixinAnnotations,
        )
    }

    private fun KMixinNode.Function.toShadowModel(
        isInterface: Boolean,
        mixinAnnotations: List<MixinAnnotationModel>,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Shadow.Function {
        kspRequire(isPublic) { "" }
        val jvmName = kspRequireNotNull(jvmName) { "" }
        kspRequire(isAbstract) { "" }
        kspRequire(extensionReceiverType == null) { "" }
        kspRequire(contextParameters.isEmpty()) { "" }
        kspRequire(!isSuspending) { "Suspending is not allowed in @KShadow." }
        val declaredName = name.validate().toModel()
        val mappingNameArgument = annotations.findApiArgument(MappingName::name)
        validateJavaIdentifierName(declaredName, "@KShadow function name", mappingNameArgument == null)
        val mappingName = mappingNameArgument?.let { (value, node) ->
            node.validateJavaIdentifierName(value, "@KShadow function's @MappingName value", sources = true)
        } ?: declaredName
        val modifiersArgument = annotations.findApiArgument(KShadow::modifiers)
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        return KMixinModel.Shadow.Function(
            declaredName = declaredName,
            jvmName = jvmName,
            mappingName = mappingName,
            parameters = parameters.map {
                FunctionParameterModel(
                    name = it.name.validate().toModel(),
                    type = it.type.validate().toModel(scopeTypeParameters),
                )
            },
            returnType = returnType?.validate()?.toModel(scopeTypeParameters),
            mixinAnnotations = mixinAnnotations,
            modifiers = validateShadowModifiers(modifiersArgument?.elements.orEmpty(), isInterface, isProperty = false),
            typeParameters = localTypeParameters,
        )
    }

    private fun KMixinNode.Function.validateAsInjection(
        isInCompanionObject: Boolean,
        targetType: ValidTypeNode,
        mixinAnnotations: List<MixinAnnotationModel>,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Injection {
        val jvmName = kspRequireNotNull(jvmName) { "" }
        kspRequire(!isOpen) { "" }
        kspRequire(!isSuspending) { "Suspending is not allowed in injections." }
        if (isInCompanionObject) {
            kspRequire(extensionReceiverType == null) { "" }
        }
        val scopeTypeParameters = typeParameters.validate(enclosingTypeParameters) + enclosingTypeParameters
        val extensionReceiverType = extensionReceiverType
            ?.validate()
            ?.requireSubtypeOf(target = targetType, roleDesc = "Injection extension receiver")
            ?.toModel(scopeTypeParameters)
        return KMixinModel.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType,
            mixinAnnotations = mixinAnnotations,
            parameters = parameters.map { it.validateAsInjectionParameter(scopeTypeParameters) },
            contextParameters = contextParameters.map { it.validateAsInjectionParameter(enclosingTypeParameters) },
            returnType = returnType?.validate()?.toModel(scopeTypeParameters),
        )
    }

    private fun KMixinNode.Function.Parameter.validateAsInjectionParameter(
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = KMixinModel.Injection.Parameter(
        name = name.validate().toModel(),
        type = type.validate().toModel(enclosingTypeParameters),
        mixinAnnotations = annotations.filterMixinAnnotations().map { it.toModel() },
    )

    private fun ContextParameterNode.validateAsInjectionParameter(
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = KMixinModel.Injection.Parameter(
        name = name.validate().toModel(),
        type = type.validate().toModel(enclosingTypeParameters),
        mixinAnnotations = annotations.filterMixinAnnotations().map { it.toModel() },
    )

    private fun ContextParameterNode.validate(
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = ContextParameterModel(
        name = name.validate().toModel(),
        type = type.validate().toModel(enclosingTypeParameters),
    )

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
                How to fix: Copy the exact modifiers of $javaMemberName from the Minecraft $containerName source code.
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
        kspRequire(SourceVersion.isIdentifier(name)) {
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

    private fun AnnotationNode.isMixinAnnotation(): Boolean {
        val type = (this as? ValidAnnotationNode)?.type as? ClassTypeNode ?: return true
        val packageName = (type.packageName as? ValidNameNode)?.name ?: return true
        return options.mixinAnnotationPackages.any { packageName.isSubpackageOf(it) }
    }

    private fun AnnotationNodeContainer.filterMixinAnnotations(): List<ValidAnnotationNode> =
        external.filter { it.isMixinAnnotation() }.validateAll { it.validate() }

    private fun AnnotationNode.validate(): ValidAnnotationNode =
        kspRequireNotNull(this as? ValidAnnotationNode) {
            """
            Annotations must be valid here.
            Why: Package name is required to determine whether the annotation should be copied into Java Mixin.
            How to fix: Ensure the annotation is correctly imported and has no compilation errors.
            """.trimIndent()
        }

    private fun ValidAnnotationNode.toModel(): MixinAnnotationModel {
        val validType = type.validate()
        kspRequire(validType is ClassTypeNode) { "" }
        return MixinAnnotationModel(
            type = validType.toModel(),
            arguments = arguments.validate(),
        )
    }

    @JvmName("validateAnnotationArguments")
    private fun List<AnnotationNode.Argument>.validate(): List<MixinAnnotationModel.Argument> =
        filter { it !is AnnotationNode.ValidArgument || it.isExplicit }.validateAll { it.validate() }

    private fun AnnotationNode.Argument.validate(): MixinAnnotationModel.Argument {
        kspRequire(this is AnnotationNode.ValidArgument) {
            """
            Mixin-related annotation arguments must be valid.
            Why: Invalid arguments cannot be mapped into Java Mixin.
            How to fix: Ensure the annotation argument has no compilation errors.
            """.trimIndent()
        }
        return when (this) {
            is AnnotationNode.ScalarArgument -> MixinAnnotationModel.ScalarArgument(
                name.validate().toModel(),
                value.toModel(),
            )

            is AnnotationNode.ArrayArgument -> MixinAnnotationModel.ArrayArgument(
                name.validate().toModel(),
                elements.validate(),
            )
        }
    }

    @JvmName("validateAnnotationArgumentValues")
    private fun List<AnnotationNode.Argument.Value>.validate(): List<MixinAnnotationModel.Argument.Value> =
        mapValid { it.toModel() }

    private fun AnnotationNode.Argument.Value.toModel(): MixinAnnotationModel.Argument.Value = when (this) {
        is AnnotationNode.Argument.BooleanValue -> MixinAnnotationModel.Argument.BooleanValue(boolean)
        is AnnotationNode.Argument.ByteValue -> MixinAnnotationModel.Argument.ByteValue(byte)
        is AnnotationNode.Argument.ShortValue -> MixinAnnotationModel.Argument.ShortValue(short)
        is AnnotationNode.Argument.IntValue -> MixinAnnotationModel.Argument.IntValue(int)
        is AnnotationNode.Argument.LongValue -> MixinAnnotationModel.Argument.LongValue(long)
        is AnnotationNode.Argument.CharValue -> MixinAnnotationModel.Argument.CharValue(char)
        is AnnotationNode.Argument.FloatValue -> MixinAnnotationModel.Argument.FloatValue(float)
        is AnnotationNode.Argument.DoubleValue -> MixinAnnotationModel.Argument.DoubleValue(double)
        is AnnotationNode.Argument.StringValue -> MixinAnnotationModel.Argument.StringValue(string)
        is AnnotationNode.Argument.TypeValue -> {
            val validTypeModel = type.validate().toModel()
            val classTypeModel = type.kspRequireNotNull(validTypeModel as? ClassTypeModel) {
                """
                Class reference argument must resolve to a valid class declaration.
                Why: The specified type could not be resolved.
                How to fix: Ensure the class argument has no compilation errors.
                """.trimIndent()
            }
            type.kspRequire(validTypeModel.arguments.all { it is ClassTypeModel.StarProjectionArgument }) {
                """
                Generic type arguments in class reference are not supported.
                Why: Generic type arguments cannot be mapped to Java Mixin annotations.
                How to fix: Remove type arguments from the class reference.
                """.trimIndent()
            }
            MixinAnnotationModel.Argument.ClassValue(classTypeModel)
        }

        is AnnotationNode.Argument.EnumValue -> {
            val validTypeModel = type.validate().toModel()
            val classTypeModel = type.kspRequireNotNull(validTypeModel as? ClassTypeModel) { "" }
            MixinAnnotationModel.Argument.EnumValue(classTypeModel, name.validate().toModel())
        }

        is AnnotationNode.Argument.AnnotationValue -> {
            MixinAnnotationModel.Argument.AnnotationValue(annotation.validate().toModel())
        }
    }

    private fun ValidTypeNode.requireSubtypeOf(target: ValidTypeNode, roleDesc: String): ValidTypeNode {
        kspRequire(!isNullable) {
            """
            $roleDesc type cannot be nullable.
            Why: In Java Mixin, an instance of the target type is always initialized and is guaranteed to be non-null.
            How to fix: Remove the nullable mark ('?') from type.
            """.trimIndent()
        }
        kspRequire(type.isSubtypeOf(target.type)) {
            """
            $roleDesc type must be a subtype of target type.
            Why: In Java Mixin, 'this' is the target type, so an unsafe cast requires a subtype relationship.
            How to fix: Ensure type is a subtype of target type.
            """.trimIndent()
        }
        return this
    }

    private fun TypeNode.validate(): ValidTypeNode =
        kspRequireNotNull(this as? ValidTypeNode) {
            """
            Ensure the type has no compilation errors.
            """.trimIndent()
        }

    private fun List<TypeParameterNode>.validate(enclosing: List<TypeParameterModel>): List<TypeParameterModel> {
        val stubs = map { TypeParameterModel(name = it.name.validate().toModel(), bounds = emptyList()) }
        return validateAll { it.toModel(enclosing + stubs) }
    }

    private fun TypeParameterNode.toModel(enclosing: List<TypeParameterModel>): TypeParameterModel {
        val name = name.validate().toModel()
        kspRequire(!isReified) {
            """
            Reified type parameter '$name' is not supported in @KMixin.
            Why: 'reified' type parameters only exist in Kotlin inline functions and cannot be used in Java.
            How to fix: Remove the 'reified' keyword from parameter.
            """.trimIndent()
        }
        return TypeParameterModel(
            name = name,
            bounds = bounds.mapValid { it.validate().toModel(enclosing) },
        )
    }

    private fun ValidTypeNode.toModel(scopeTypeParameters: List<TypeParameterModel> = emptyList()): TypeModel =
        when (this) {
            is ClassTypeNode -> toModel(scopeTypeParameters)
            is TypeArgumentNode -> {
                val name = name.validate().toModel()
                val typeParameter = findParameter(name, scopeTypeParameters)
                val canonicalType = resolveCanonicalType(typeParameter, scopeTypeParameters)
                TypeArgumentModel(name = name, canonicalType = canonicalType, isNullable = isNullable)
            }
        }

    private fun ClassTypeNode.toModel(scopeTypeParameters: List<TypeParameterModel> = emptyList()): ClassTypeModel {
        val functionalType = functionalType?.let { type ->
            kspRequire(!type.isSuspending) { "Suspending functional types are not supported yet." }
            ClassTypeModel.FunctionalType(
                contextTypes = type.contextTypes.map { it.validate().toModel(scopeTypeParameters) },
                receiverType = type.receiverType?.validate()?.toModel(scopeTypeParameters),
                parameters = type.parameters.map {
                    ClassTypeModel.FunctionalType.Parameter(
                        name = it.name,
                        type = it.type.validate().toModel(scopeTypeParameters),
                    )
                },
                returnType = type.returnType.validate().toModel(scopeTypeParameters),
            )
        }
        return ClassTypeModel(
            packageName = kspRequireNotNull((packageName as? ValidNameNode)?.name) {
                """
                Class declarations must belong to a package.
                Why: Java Mixin in a named package cannot access declarations in the default package.
                How to fix: Move the class declaration into a named package.
                """.trimIndent()
            },
            qualifiedName = kspRequireNotNull((qualifiedName as? ValidNameNode)?.name) {
                """
                Class declarations must have a fully qualified name; local classes are not supported.
                Why: Java Mixin cannot access local class declarations.
                How to fix: Move the class declaration out of the local scope.
                """.trimIndent()
            },
            arguments = arguments.map { it.validate(scopeTypeParameters) },
            canonicalType = canonicalType?.toModel(scopeTypeParameters),
            functionalType = functionalType,
            isNullable = isNullable,
        )
    }

    private fun ClassTypeNode.TypeArgument.validate(
        scopeTypeParameters: List<TypeParameterModel>
    ): ClassTypeModel.TypeArgument = when (this) {
        is ClassTypeNode.StarProjectionArgument -> ClassTypeModel.StarProjectionArgument
        is ClassTypeNode.GenericTypeArgument -> ClassTypeModel.GenericTypeArgument(
            type = type.validate().toModel(scopeTypeParameters),
            variance = variance,
        )
    }

    private fun TypeArgumentNode.findParameter(
        name: String,
        scopeTypeParameters: List<TypeParameterModel>,
    ): TypeParameterModel = kspRequireNotNull(scopeTypeParameters.find { it.name == name }) {
        "Type parameter '$name' not found in scope"
    }

    private tailrec fun TypeArgumentNode.resolveCanonicalType(
        typeParameter: TypeParameterModel,
        scopeTypeParameters: List<TypeParameterModel>,
        visited: Set<String> = emptySet(),
    ): ClassTypeModel {
        kspRequire(typeParameter.name !in visited) {
            "Cyclic type parameter boundary detected for ${typeParameter.name}"
        }
        val firstBound = typeParameter.bounds.firstOrNull() ?: ClassTypeModel.NULLABLE_ANY
        return when (firstBound) {
            is ClassTypeModel -> firstBound
            is TypeArgumentModel -> {
                val nextTypeParameter = findParameter(firstBound.name, scopeTypeParameters)
                resolveCanonicalType(nextTypeParameter, scopeTypeParameters, visited + typeParameter.name)
            }
        }
    }

    private fun NameNode.validate(): ValidNameNode =
        kspRequireNotNull(this as? ValidNameNode) {
            """
            Ensure the name has no compilation errors.
            """.trimIndent()
        }

    private fun ValidNameNode.toModel(): String = name

    private inline fun NodeHolder.kspError(crossinline message: () -> String): Nothing {
        node.report(message())
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

    private inline fun <T, R : Any> Sequence<T>.mapValid(crossinline block: (T) -> R): Sequence<R> =
        mapNotNull {
            try {
                block(it)
            } catch (_: InvalidSymbolSignal) {
                null
            }
        }

    private inline fun <T, R : Any> Iterable<T>.mapValid(block: (T) -> R): List<R> =
        mapNotNull {
            try {
                block(it)
            } catch (_: InvalidSymbolSignal) {
                null
            }
        }

    private inline fun <T, R : Any> Iterable<T>.validateAll(block: (T) -> R): List<R> {
        var hasError = false
        val result = mapNotNull {
            try {
                block(it)
            } catch (_: InvalidSymbolSignal) {
                hasError = true
                null
            }
        }
        if (hasError) throw InvalidSymbolSignal()
        return result
    }

    private class InvalidSymbolSignal : Exception()
}

// TODO: User-friendly errors
