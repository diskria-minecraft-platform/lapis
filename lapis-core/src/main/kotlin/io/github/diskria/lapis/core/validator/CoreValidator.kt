package io.github.diskria.lapis.core.validator

import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.isSubpackageOf
import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.JavaModifiers
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.core.validator.Diagnostic.*
import io.github.diskria.lapis.core.validator.models.*
import io.github.diskria.lapis.core.validator.models.KMixinModel.TargetTypeParameterModel
import java.util.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class CoreValidator<O>(private val options: CliOptions) {

    private val diagnosticReports = mutableListOf<DiagnosticReport>()

    fun validate(node: KMixinNode<O>): Pair<KMixinModel<O>?, List<DiagnosticReport>> {
        val model = node.ifValid { it.validate() }
        val result = model to diagnosticReports.toList()
        diagnosticReports.clear()
        return result
    }

    private fun KMixinNode<O>.validate(): KMixinModel<O> {
        nodeRequire(isTopLevel) { KMixinMustBeTopLevel }
        nodeRequire(isPublic) { KMixinMustBePublic }
        nodeRequire(!isSealed) { KMixinCannotBeSealed }
        nodeRequire(!isOpen) { CannotBeOpen("'@KMixin'") }
        val mixinAnnotations = annotations.filterMixinAnnotations()
        val initStrategy = annotations.findApiArgument(KMixin::initStrategy)?.value ?: InitStrategy.Lazy
        val side = annotations.findApiArgument(KMixin::side)?.value ?: Side.Common
        val targetArgument = nodeRequireNotNull(annotations.findApiArgument(KMixin::target)) {
            KMixinTargetRequired
        }
        val targetClassType = nodeRequireNotNull(targetArgument.value.validate() as? ParsedClassType) {
            InvalidType("'@KMixin' target")
        }
        val classType = nodeRequireNotNull(type.validate() as? ParsedClassType) {
            InvalidType("'@KMixin'")
        }
        nodeRequire(typeParameters.isEmpty()) { KMixinTypeParametersNotSupported }
        val typeParameters = emptyList<TargetTypeParameterModel>()
        val shadowProperties = mutableListOf<KMixinModel.Shadow.Property>()
        val extensionProperties = mutableListOf<KMixinModel.Extension.Property>()
        properties.mapValid { property ->
            val getterMixinAnnotations = property.getter?.annotations?.filterMixinAnnotations().orEmpty()
                .map { it.toModel() }
            val hasShadowAnnotation = property.annotations.hasApiAnnotation<KShadow>()
            val hasExtensionAnnotation = property.annotations.hasApiAnnotation<Extension>()
            if (hasShadowAnnotation && hasExtensionAnnotation) {
                property.nodeError {
                    ConflictingAnnotations("Property", "'@KShadow'", "'@Extension'")
                }
            }
            if (hasShadowAnnotation) {
                shadowProperties += property.toShadowModel(isInterface, getterMixinAnnotations, typeParameters)
            } else if (hasExtensionAnnotation) {
                property.nodeRequire(getterMixinAnnotations.isEmpty()) {
                    CannotHaveMixinAnnotations("Extension property getter")
                }
                extensionProperties += property.toExtensionModel(isClass, typeParameters)
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
                function.nodeError {
                    ConflictingAnnotations("Function", "'@KShadow'", "'@Extension'")
                }
            }
            if (hasShadowAnnotation) {
                shadowFunctions += function.toShadowModel(isClass, mixinAnnotations, typeParameters)
            } else if (hasExtensionAnnotation) {
                function.nodeRequire(mixinAnnotations.isEmpty()) {
                    CannotHaveMixinAnnotations("Extension functions")
                }
                extensionFunctions += function.toExtensionModel(isClass, typeParameters)
            } else if (mixinAnnotations.isNotEmpty()) {
                injections += function.validateAsInjection(
                    isInClass = isClass,
                    isInCompanionObject = false,
                    targetClassType,
                    mixinAnnotations,
                    typeParameters,
                )
            }
        }
        val companionObject = companionObject?.let { companionObject ->
            val injections = mutableListOf<KMixinModel.Injection>()
            companionObject.functions.mapValid { function ->
                function.nodeRequire(!function.annotations.hasApiAnnotation<KShadow>()) {
                    CompanionObjectUnsupportedAnnotation("'@KShadow'")
                }
                function.nodeRequire(!function.annotations.hasApiAnnotation<Extension>()) {
                    CompanionObjectUnsupportedAnnotation("'@Extension'")
                }
                val mixinAnnotations = function.annotations.filterMixinAnnotations().map { it.toModel() }
                if (mixinAnnotations.isNotEmpty()) {
                    injections += function.validateAsInjection(
                        isInClass = isClass,
                        isInCompanionObject = true,
                        targetClassType,
                        mixinAnnotations,
                        typeParameters,
                    )
                }
            }
            if (injections.isNotEmpty()) {
                companionObject.nodeRequire(companionObject.isPublic) {
                    MustBePublic("Companion object containing mixin injections")
                }
            }
            KMixinModel.CompanionObject(name = companionObject.name, injections = injections)
        }
        val classKind = if (isClass) {
            val constructor = nodeRequireNotNull(constructors.singleOrNull()) {
                KMixinMultipleConstructors
            }
            constructor.nodeRequire(constructor.isPublic) {
                KMixinConstructorMustBePublic
            }
            KMixinModel.Class(
                isAbstract = isAbstract,
                constructorParameters = constructor.parameters.mapValid {
                    it.toModel(targetClassType, typeParameters)
                },
            )
        } else if (isInterface) {
            KMixinModel.Interface
        } else {
            nodeError { KMixinMustBeClassOrInterface }
        }
        return KMixinModel(
            origin = origin,
            type = classType.toModel(),
            name = name,
            side = side,
            initStrategy = initStrategy,
            classKind = classKind,
            targetType = targetClassType.toModel(),
            shadowSources = if (isAbstract || isInterface) shadowProperties + shadowFunctions else emptyList(),
            extensionSources = extensionProperties + extensionFunctions,
            injections = injections,
            companionObject = companionObject,
            mixinAnnotations = mixinAnnotations.map { it.toModel() },
            typeParameters = typeParameters,
        )
    }

    private fun KMixinNode.Constructor.Parameter.toModel(
        targetType: ParsedClassType,
        scopeTypeParameters: List<TypeParameterModel>,
    ) = when {
        annotations.hasApiAnnotation<Origin>() -> {
            val name = nodeRequireNotNull(name) { ParameterMustHaveName }
            KMixinModel.Class.ConstructorParameter.Origin(
                name = name,
                type = type.validate()
                    .requireSupertypeOf(
                        target = targetType,
                        node = this,
                        useSiteDesc = "'@Origin' constructor parameter",
                        targetStrategy = TargetStrategy.RETURN_TYPE,
                    )
                    .toModel(scopeTypeParameters),
            )
        }

        else -> nodeError { KMixinUnexpectedConstructorParameter }
    }

    private fun KMixinNode.Property.toExtensionModel(
        isInClass: Boolean,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Extension.Property {
        nodeRequire(isPublic) { MustBePublic("'@Extension' declarations") }
        nodeRequire(!isAbstract) { CannotBeAbstract("'@Extension' declarations") }
        if (isInClass) {
            nodeRequire(!isOpen) { CannotBeOpen("'@Extension' declarations in classes") }
        }
        nodeRequire(hasExplicitType) { MustHaveExplicitType("'@Extension' properties", "type") }
        nodeRequire(!hasExtensionReceiver) { CannotHaveReceiver("'@Extension' declarations") }
        val getter = nodeRequireNotNull(getter) {
            FailedToParseGetter("'@Extension' property")
        }
        val getterJvmName = nodeRequireNotNull(getter.jvmName) {
            FailedToParseJvmName("'@Extension' property getter")
        }
        val setterJvmName = setter?.let {
            nodeRequireNotNull(setter.jvmName) {
                FailedToParseJvmName("'@Extension' property setter")
            }
        }
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        return KMixinModel.Extension.Property(
            declaredName = name,
            getterJvmName = getterJvmName,
            setterJvmName = setterJvmName,
            type = type.validate().toModel(scopeTypeParameters),
            typeParameters = localTypeParameters,
            contextParameters = contextParameters.mapValid { it.validate(scopeTypeParameters) },
        )
    }

    private fun KMixinNode.Function.toExtensionModel(
        isInClass: Boolean,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Extension.Function {
        nodeRequire(isPublic) { MustBePublic("'@Extension' declarations") }
        val jvmName = nodeRequireNotNull(jvmName) {
            FailedToParseJvmName("'@Extension' function")
        }
        nodeRequire(!isAbstract) { CannotBeAbstract("'@Extension' declarations") }
        if (isInClass) {
            nodeRequire(!isOpen) { CannotBeOpen("'@Extension' declarations in classes") }
        }
        nodeRequire(hasExplicitReturnType) { MustHaveExplicitType("'@Extension' functions", "return type") }
        nodeRequire(extensionReceiverType == null) { CannotHaveReceiver("'@Extension' declarations") }
        nodeRequire(!isSuspending) { CoroutinesNotSupported() }
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        val parameters = parameters.map { parameter ->
            FunctionParameterModel(
                name = parameter.nodeRequireNotNull(parameter.name) { ParameterMustHaveName },
                type = parameter.type.validate().toModel(scopeTypeParameters),
            )
        }
        return KMixinModel.Extension.Function(
            declaredName = name,
            jvmName = jvmName,
            parameters = parameters,
            returnType = returnType.validate().toModel(scopeTypeParameters),
            typeParameters = localTypeParameters,
            contextParameters = contextParameters.mapValid { it.validate(scopeTypeParameters) },
        )
    }

    private fun KMixinNode.Property.toShadowModel(
        isInterface: Boolean,
        mixinAnnotations: List<MixinAnnotationModel>,
        scopeTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Shadow.Property {
        nodeRequire(isPublic) { MustBePublic("'@KShadow' declarations") }
        nodeRequire(isAbstract) { MustBeAbstract("'@KShadow' declarations") }
        nodeRequire(!hasExtensionReceiver) { CannotHaveReceiver("'@KShadow' declarations") }
        nodeRequire(contextParameters.isEmpty()) { CannotHaveContextParameters("'@KShadow' declarations") }
        nodeRequire(typeParameters.isEmpty()) { CannotHaveTypeParameters("'@KShadow' properties") }
        val getter = nodeRequireNotNull(getter) {
            FailedToParseGetter("'@KShadow' property")
        }
        val getterJvmName = nodeRequireNotNull(getter.jvmName) {
            FailedToParseJvmName("'@KShadow' property getter")
        }
        val setterJvmName = setter?.let {
            nodeRequireNotNull(it.jvmName) {
                FailedToParseJvmName("'@KShadow' property setter")
            }
        }
        val mappingName = annotations.getEffectiveMappingName(name)
        validateJavaIdentifierName(name, "'@KShadow' property name")
        val kShadowAnnotation = nodeRequireNotNull(annotations.findApiAnnotation<KShadow>()) {
            Other("'@KShadow' annotation is not found")
        }
        val modifiers = annotations.findApiArgument(KShadow::modifiers)?.elements.orEmpty()
        return KMixinModel.Shadow.Property(
            declaredName = name,
            getterJvmName = getterJvmName,
            setterJvmName = setterJvmName,
            mappingName = mappingName,
            modifiers = kShadowAnnotation.validateShadowModifiers(modifiers, isInterface, isProperty = true),
            type = type.validate().toModel(scopeTypeParameters),
            mixinAnnotations = mixinAnnotations,
        )
    }

    private fun KMixinNode.Function.toShadowModel(
        isInClass: Boolean,
        mixinAnnotations: List<MixinAnnotationModel>,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Shadow.Function {
        nodeRequire(isPublic) { MustBePublic("'@KShadow' declarations") }
        nodeRequire(isAbstract) { MustBeAbstract("'@KShadow' declarations") }
        if (isInClass) {
            nodeRequire(!isOpen) { CannotBeOpen("'@KShadow' declarations in classes") }
        }
        nodeRequire(extensionReceiverType == null) { CannotHaveReceiver("'@KShadow' declarations") }
        nodeRequire(contextParameters.isEmpty()) { CannotHaveContextParameters("'@KShadow' declarations") }
        nodeRequire(!isSuspending) { CoroutinesNotSupported("'@KShadow'") }
        val jvmName = nodeRequireNotNull(jvmName) {
            FailedToParseJvmName("'@KShadow' function")
        }
        val mappingName = annotations.getEffectiveMappingName(name)
        validateJavaIdentifierName(name, "'@KShadow' function name")
        val kShadowAnnotation = nodeRequireNotNull(annotations.findApiAnnotation<KShadow>()) {
            Other("'@KShadow' annotation is not found")
        }
        val modifiers = annotations.findApiArgument(KShadow::modifiers)?.elements.orEmpty()
        val localTypeParameters = typeParameters.validate(enclosingTypeParameters)
        val scopeTypeParameters = localTypeParameters + enclosingTypeParameters
        return KMixinModel.Shadow.Function(
            declaredName = name,
            jvmName = jvmName,
            mappingName = mappingName,
            parameters = parameters.map { it.validateAsShadowFunctionParameter(scopeTypeParameters) },
            returnType = returnType.validate().toModel(scopeTypeParameters),
            mixinAnnotations = mixinAnnotations,
            modifiers = kShadowAnnotation.validateShadowModifiers(modifiers, !isInClass, isProperty = false),
            typeParameters = localTypeParameters,
        )
    }

    private fun KMixinNode.Function.Parameter.validateAsShadowFunctionParameter(
        scopeTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Shadow.Function.Parameter {
        val name = nodeRequireNotNull(name) { ParameterMustHaveName }
        val mappingName = annotations.getEffectiveMappingName(name)
        validateJavaIdentifierName(name, "'@KShadow' function parameter name")
        return KMixinModel.Shadow.Function.Parameter(
            name = name,
            mappingName = mappingName,
            type = type.validate().toModel(scopeTypeParameters),
        )
    }

    private fun KMixinNode.Function.validateAsInjection(
        isInClass: Boolean,
        isInCompanionObject: Boolean,
        targetType: ParsedClassType,
        mixinAnnotations: List<MixinAnnotationModel>,
        enclosingTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Injection {
        nodeRequire(isPublic) { InjectionFunctionMustBePublic }
        val jvmName = nodeRequireNotNull(jvmName) {
            FailedToParseJvmName("injection function")
        }
        nodeRequire(!isAbstract) { CannotBeAbstract("Injection functions") }
        if (isInClass) {
            nodeRequire(!isOpen) { CannotBeOpen("Injection functions in classes") }
        }
        nodeRequire(!isSuspending) { CoroutinesNotSupported("injections") }
        if (isInCompanionObject) {
            nodeRequire(extensionReceiverType == null) {
                InjectionReceiverOnlyInInstanceContext
            }
        }
        val scopeTypeParameters = typeParameters.validate(enclosingTypeParameters) + enclosingTypeParameters
        val extensionReceiverType = extensionReceiverType
            ?.validate()
            ?.requireSupertypeOf(
                target = targetType,
                node = this,
                useSiteDesc = "injection function receiver",
                targetStrategy = TargetStrategy.RECEIVER_TYPE,
            )
            ?.toModel(scopeTypeParameters)
        return KMixinModel.Injection(
            jvmName = jvmName,
            extensionReceiverType = extensionReceiverType,
            mixinAnnotations = mixinAnnotations,
            parameters = parameters.map { it.validateAsInjectionParameter(scopeTypeParameters) },
            contextParameters = contextParameters.map { it.validateAsInjectionParameter(enclosingTypeParameters) },
            returnType = returnType.validate().toModel(scopeTypeParameters),
        )
    }

    private fun KMixinNode.Function.Parameter.validateAsInjectionParameter(
        scopeParameters: List<TypeParameterModel>,
    ): KMixinModel.Injection.Parameter {
        val name = nodeRequireNotNull(name) { ParameterMustHaveName }
        return KMixinModel.Injection.Parameter(
            name = name,
            type = type.validate().toModel(scopeParameters),
            mixinAnnotations = annotations.filterMixinAnnotations().map { it.toModel() },
        )
    }

    private fun ContextParameterNode.validateAsInjectionParameter(
        scopeTypeParameters: List<TypeParameterModel>,
    ): KMixinModel.Injection.Parameter {
        val name = nodeRequireNotNull(name) { ParameterMustHaveName }
        return KMixinModel.Injection.Parameter(
            name = name,
            type = type.validate().toModel(scopeTypeParameters),
            mixinAnnotations = annotations.filterMixinAnnotations().map { it.toModel() },
        )
    }

    private fun ContextParameterNode.validate(scopeTypeParameters: List<TypeParameterModel>): ContextParameterModel {
        val name = nodeRequireNotNull(name) { ParameterMustHaveName }
        return ContextParameterModel(
            name = name,
            type = type.validate().toModel(scopeTypeParameters),
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

        nodeRequire(STATIC !in rawModifiers) {
            StaticShadowMustBeInCompanion
        }

        val memberDesc = if (isProperty) "property" else "function"

        fun Iterable<Modifier>.joinToUppercase(): String = joinToString(prefix = "[", postfix = "]") { "'${it.name}'" }
        val allowedModifiers = if (isProperty) JavaModifiers.FIELD_ALLOWED else JavaModifiers.METHOD_ALLOWED
        val invalidModifiers = result.filter { it !in allowedModifiers }
        nodeRequire(invalidModifiers.isEmpty()) {
            ShadowInvalidModifiers(memberDesc, invalidModifiers.joinToUppercase())
        }
        val visibilities = result.filter { it in JavaModifiers.VISIBILITIES }
        nodeRequire(visibilities.size <= 1) {
            ShadowMultipleVisibilityModifiers(memberDesc, visibilities.joinToUppercase())
        }
        if (isProperty) {
            if (FINAL in result) {
                nodeRequire(VOLATILE !in result) {
                    ShadowConflictingModifiers(memberDesc, "FINAL", "VOLATILE")
                }
            }
        } else {
            if (ABSTRACT in result) {
                val illegalAbstractModifiers = result.filter { it in JavaModifiers.ABSTRACT_ILLEGALS }
                nodeRequire(illegalAbstractModifiers.isEmpty()) {
                    ShadowIllegalAbstractModifiers(
                        memberDesc,
                        illegalAbstractModifiers.joinToUppercase()
                    )
                }
            }
            if (NATIVE in result) {
                nodeRequire(DEFAULT !in result) {
                    ShadowConflictingModifiers(memberDesc, "NATIVE", "DEFAULT")
                }
            }
            if (isInterface) {
                if (PRIVATE in result) {
                    nodeRequire(DEFAULT !in result) {
                        ShadowConflictingModifiers(memberDesc, "PRIVATE", "DEFAULT", "interface")
                    }
                    nodeRequire(ABSTRACT !in result) {
                        ShadowConflictingModifiers(memberDesc, "PRIVATE", "ABSTRACT", "interface")
                    }
                } else {
                    val executionTypes = result.filter { it in JavaModifiers.EXECUTION_TYPES }
                    nodeRequire(executionTypes.size == 1) {
                        ShadowInterfaceExecutionType(
                            expected = JavaModifiers.EXECUTION_TYPES.joinToUppercase(),
                            found = executionTypes.joinToUppercase(),
                        )
                    }
                }
            } else {
                nodeRequire(DEFAULT !in result) {
                    DefaultModifierOutsideInterface
                }
            }
        }
        return result
    }

    private fun NodeHolder.validateJavaIdentifierName(
        name: String,
        roleDesc: String,
    ): String {
        nodeRequire(SourceVersion.isIdentifier(name)) {
            InvalidJavaIdentifier(roleDesc, name)
        }
        return name
    }

    private fun AnnotationNode.isMixinAnnotation(): Boolean {
        val type = type.validate() as? ParsedClassType ?: return true
        return options.mixinAnnotationPackages.any { type.packageName.isSubpackageOf(it) }
    }

    private fun AnnotationsContainer.filterMixinAnnotations(): List<AnnotationNode> =
        externalAnnotations.filter { it.isMixinAnnotation() }

    private fun AnnotationNode.toModel(): MixinAnnotationModel {
        nodeRequire(type is ParsedClassType) {
            InvalidAnnotationType
        }
        return MixinAnnotationModel(
            type = type.toModel(),
            arguments = arguments.validateAll { it.validate() },
        )
    }

    private fun AnnotationNode.Argument.validate(): MixinAnnotationModel.Argument {
        nodeRequire(this is AnnotationNode.ValidArgument) {
            InvalidAnnotationArgument
        }
        return when (this) {
            is AnnotationNode.ScalarArgument -> {
                MixinAnnotationModel.ScalarArgument(name, value.validate().toModel())
            }

            is AnnotationNode.ArrayArgument -> {
                MixinAnnotationModel.ArrayArgument(name, elements.mapValid { it.validate().toModel() })
            }
        }
    }

    context(nodeHolder: NodeHolder)
    private fun AnnotationNode.Argument.Value.validate(): AnnotationNode.Argument.ValidValue =
        nodeHolder.nodeRequireNotNull(this as? AnnotationNode.Argument.ValidValue) {
            InvalidAnnotationArgumentValue
        }

    context(nodeHolder: NodeHolder)
    private fun AnnotationNode.Argument.ValidValue.toModel() = when (this) {
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
            val typeModel = type.validate().toModel()
            val classTypeModel = nodeHolder.nodeRequireNotNull(typeModel as? ClassTypeModel) {
                ClassReferenceMustBeClass
            }
            nodeHolder.nodeRequire(typeModel.typeArguments.all { it is ClassTypeModel.StarProjectionArgument }) {
                GenericTypeArgumentsInClassReferenceNotSupported
            }
            MixinAnnotationModel.Argument.ClassValue(classTypeModel)
        }

        is AnnotationNode.Argument.EnumValue -> {
            val typeModel = type.validate().toModel()
            val classTypeModel = nodeHolder.nodeRequireNotNull(typeModel as? ClassTypeModel) {
                EnumTypeMustBeClass
            }
            MixinAnnotationModel.Argument.EnumValue(classTypeModel, name)
        }

        is AnnotationNode.Argument.AnnotationValue -> {
            MixinAnnotationModel.Argument.AnnotationValue(annotation.toModel())
        }
    }

    private fun ParsedValidType.requireSupertypeOf(
        target: ParsedClassType,
        node: NodeHolder,
        useSiteDesc: String,
        targetStrategy: TargetStrategy,
    ): ParsedValidType {
        node.nodeRequire(type.isSupertypeOf(target.type)) {
            TargetTypeMismatch(useSiteDesc, targetStrategy, type, target.type)
        }
        if (type.isNullable) {
            node.nodeWarn { RedundantNullableTargetType(useSiteDesc, targetStrategy) }
        }
        return this
    }

    context(nodeHolder: NodeHolder)
    private fun ParsedType.validate(): ParsedValidType =
        nodeHolder.nodeRequireNotNull(this as? ParsedValidType) {
            InvalidTypeDeclaration
        }

    private fun List<TypeParameterNode>.validate(enclosing: List<TypeParameterModel>): List<TypeParameterModel> {
        val stubs = map { TypeParameterModel(name = it.name) }
        return validateAll { it.toModel(enclosing + stubs) }
    }

    private fun TypeParameterNode.toModel(enclosing: List<TypeParameterModel>): TypeParameterModel {
        nodeRequire(!isReified) {
            ReifiedTypeParameterNotSupported(name)
        }
        return TypeParameterModel(
            name = name,
            mappingName = annotations.getEffectiveMappingName(name),
            bounds = bounds.mapValid { it.validate().toModel(enclosing) },
        )
    }

    context(nodeHolder: NodeHolder)
    private fun ParsedValidType.toModel(scopeTypeParameters: List<TypeParameterModel> = emptyList()): TypeModel =
        when (this) {
            is ParsedClassType -> toModel(scopeTypeParameters)
            is ParsedTypeParameterType -> {
                val typeParameter = findTypeParameter(name, scopeTypeParameters)
                val upperBound = unwrapUpperBound(typeParameter, scopeTypeParameters)
                TypeParameterTypeModel(name = name, upperBound = upperBound, isNullable = type.isNullable)
            }
        }

    context(nodeHolder: NodeHolder)
    private fun ParsedClassType.toModel(scopeTypeParameters: List<TypeParameterModel> = emptyList()): ClassTypeModel {
        val functionalType = functionalType?.let { type ->
            nodeHolder.nodeRequire(!type.isSuspending) { CoroutinesNotSupported() }
            ClassTypeModel.FunctionalType(
                contextTypes = type.contextTypes.map { it.toModel(scopeTypeParameters) },
                receiverType = type.receiverType?.toModel(scopeTypeParameters),
                parameters = type.parameters.map {
                    ClassTypeModel.FunctionalType.Parameter(
                        name = it.name,
                        type = it.type.toModel(scopeTypeParameters),
                    )
                },
                returnType = type.returnType.toModel(scopeTypeParameters),
            )
        }
        nodeHolder.nodeRequire(packageName.isNotEmpty()) {
            ClassMustBelongToPackage
        }
        return ClassTypeModel(
            packageName = packageName,
            qualifiedName = nodeHolder.nodeRequireNotNull(qualifiedName) {
                ClassMustHaveQualifiedName
            },
            typeArguments = typeArguments.map { it.validate(scopeTypeParameters) },
            actualType = actualType?.toModel(scopeTypeParameters),
            functionalType = functionalType,
            isNullable = type.isNullable,
        )
    }

    context(nodeHolder: NodeHolder)
    private fun ParsedClassType.TypeArgument.validate(scopeTypeParameters: List<TypeParameterModel>) = when (this) {
        is ParsedClassType.StarProjectionArgument -> ClassTypeModel.StarProjectionArgument
        is ParsedClassType.TypeProjectionArgument -> ClassTypeModel.TypeProjectionArgument(
            type = type.toModel(scopeTypeParameters),
            variance = variance,
        )
    }

    context(nodeHolder: NodeHolder)
    private fun findTypeParameter(name: String, scopeTypeParameters: List<TypeParameterModel>): TypeParameterModel =
        nodeHolder.nodeRequireNotNull(scopeTypeParameters.find { it.name == name }) {
            TypeParameterNotFoundInScope(name)
        }

    context(nodeHolder: NodeHolder)
    private tailrec fun ParsedTypeParameterType.unwrapUpperBound(
        typeParameter: TypeParameterModel,
        scopeTypeParameters: List<TypeParameterModel>,
        visited: Set<String> = emptySet(),
    ): ClassTypeModel {
        nodeHolder.nodeRequire(typeParameter.name !in visited) {
            CyclicTypeParameterBound(typeParameter.name)
        }
        return when (val upperBound = typeParameter.bounds.firstOrNull() ?: ClassTypeModel.NULLABLE_ANY) {
            is ClassTypeModel -> upperBound
            is TypeParameterTypeModel -> unwrapUpperBound(
                typeParameter = findTypeParameter(name = upperBound.name, scopeTypeParameters = scopeTypeParameters),
                scopeTypeParameters = scopeTypeParameters,
                visited = visited + typeParameter.name,
            )
        }
    }

    context(nodeHolder: NodeHolder)
    private fun AnnotationsContainer.getEffectiveMappingName(fallbackName: String): String =
        findApiArgument(MappingName::value)
            ?.let { (value, node) -> node.validateJavaIdentifierName(value, "@MappingName value") }
            ?: fallbackName

    private fun List<TypeParameterNode>.toModels(
        targetNodes: List<TypeParameterNode>,
    ): List<KMixinModel.TargetTypeParameterModel> {
        val candidateModels = validate(enclosing = emptyList())
        val mappingNames = candidateModels.associate { it.name to it.mappingName }
        val targetIndices = targetNodes.withIndex().associate { it.value.name to it.index }
        return zip(candidateModels).map { (candidateNode, candidateModel) ->
            val targetIndex = candidateNode.nodeRequireNotNull(targetIndices[candidateModel.mappingName]) {
                TargetTypeParameterNotFound(
                    name = candidateModel.mappingName,
                    existing = targetNodes.joinToString { it.name },
                )
            }
            val targetNode = targetNodes[targetIndex]
            candidateNode.nodeRequire(candidateModel.bounds.size == targetNode.bounds.size) {
                BoundsCountMismatch(
                    name = candidateModel.name,
                    expected = targetNode.bounds.size,
                    actual = candidateModel.bounds.size,
                )
            }
            candidateModel.bounds.zip(targetNode.bounds).forEach { (candidateBound, targetBound) ->
                candidateNode.requireBoundEquals(candidateBound, targetBound, mappingNames)
            }
            KMixinModel.TargetTypeParameterModel(
                name = candidateModel.name,
                mappingName = candidateModel.mappingName,
                bounds = candidateModel.bounds,
                targetIndex = targetIndex,
            )
        }
    }

    private fun NodeHolder.requireBoundEquals(
        candidate: TypeModel,
        target: ParsedType,
        mappingNames: Map<String, String>,
        visited: Set<Pair<String, String>> = emptySet(),
    ) {
        fun visitedEntry(candidate: TypeParameterTypeModel, target: ParsedTypeParameterType) =
            candidate.name to target.name

        nodeRequire(target is ParsedValidType) {
            BoundsMismatch("target is invalid")
        }
        when (target) {
            is ParsedClassType -> {
                nodeRequire(candidate is ClassTypeModel) {
                    BoundsMismatch("expected class, but got type parameter")
                }
                val candidateActual = candidate.actualOrThis
                nodeRequire(candidateActual.qualifiedName == target.qualifiedName) {
                    BoundsMismatch("expected ${target.qualifiedName}, but got ${candidateActual.qualifiedName}")
                }
                nodeRequire(candidateActual.typeArguments.size == target.typeArguments.size) {
                    BoundsMismatch("expected ${target.typeArguments.size} arguments, but got ${candidateActual.typeArguments.size}")
                }
                candidateActual.typeArguments.zip(target.typeArguments).forEach { (candidateArgument, targetArgument) ->
                    when (targetArgument) {
                        is ParsedClassType.StarProjectionArgument -> {
                            nodeRequire(candidateArgument is ClassTypeModel.StarProjectionArgument) {
                                BoundsMismatch("expected star projection")
                            }
                        }

                        is ParsedClassType.TypeProjectionArgument -> {
                            nodeRequire(candidateArgument is ClassTypeModel.TypeProjectionArgument) {
                                BoundsMismatch("expected type projection")
                            }
                            if (candidateArgument.variance != Variance.INVARIANT) {
                                nodeRequire(candidateArgument.variance == targetArgument.variance) {
                                    BoundsMismatch("expected ${targetArgument.variance}, but got ${candidateArgument.variance}")
                                }
                            }
                            val candidateType = candidateArgument.type
                            val targetType = targetArgument.type
                            requireBoundEquals(
                                candidate = candidateType,
                                target = targetType,
                                mappingNames = mappingNames,
                                visited = if (candidateType is TypeParameterTypeModel && targetType is ParsedTypeParameterType) {
                                    visited + visitedEntry(candidateType, targetType)
                                } else visited,
                            )
                        }
                    }
                }
            }

            is ParsedTypeParameterType -> {
                nodeRequire(candidate is TypeParameterTypeModel) {
                    BoundsMismatch("expected type parameter type, but got class type")
                }
                if (visitedEntry(candidate, target) in visited) return
                nodeRequire(mappingNames[candidate.name] == target.name) {
                    BoundsMismatch("expected ${target.name}, but got ${mappingNames[candidate.name]}")
                }
            }
        }
    }

    private inline fun NodeHolder.nodeError(crossinline message: () -> Error): Nothing {
        diagnosticReports += DiagnosticReport(
            diagnostic = message(),
            target = node,
        )
        throw InvalidSymbolSignal()
    }

    private inline fun NodeHolder.nodeWarn(crossinline message: () -> Warning) {
        diagnosticReports += DiagnosticReport(
            diagnostic = message(),
            target = node,
        )
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun NodeHolder.nodeRequire(condition: Boolean, crossinline message: () -> Error) {
        contract { returns() implies condition }
        if (!condition) {
            nodeError(message = message)
        }
    }

    @OptIn(ExperimentalContracts::class)
    private inline fun <T> NodeHolder.nodeRequireNotNull(
        value: T?,
        crossinline message: () -> Error,
    ): T {
        contract { returns() implies (value != null) }
        return value ?: nodeError(message = message)
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Calling 'nodeRequireNotNull' with non-nullable value is redundant. " +
            "Use 'nodeRequire' if checking a Boolean condition, " +
            "or remove this check if it is unnecessary.",
        level = DeprecationLevel.ERROR,
    )
    private inline fun <T : Any> NodeHolder.nodeRequireNotNull(
        value: T,
        crossinline message: () -> Diagnostic,
    ): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    @Suppress("unused", "UnusedReceiverParameter")
    @Deprecated(
        message = "Calling 'nodeRequireNotNull' with nullable Boolean is ambiguous. " +
            "Use 'nodeRequire' with an explicit condition instead.",
        replaceWith = ReplaceWith("nodeRequire(value == true, message)"),
        level = DeprecationLevel.ERROR,
    )
    private fun NodeHolder.nodeRequireNotNull(value: Boolean?, message: () -> Diagnostic): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    private inline fun <T, R : Any> T.ifValid(crossinline block: (T) -> R): R? =
        try {
            block(this)
        } catch (_: InvalidSymbolSignal) {
            null
        }

    private inline fun <T, R : Any> Sequence<T>.mapValid(crossinline block: (T) -> R): Sequence<R> =
        mapNotNull { it.ifValid(block) }

    private inline fun <T, R : Any> Iterable<T>.mapValid(crossinline block: (T) -> R): List<R> =
        mapNotNull { it.ifValid(block) }

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
