package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.KMixin
import io.github.diskria.lapis.core.extensions.internalError
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.extensions.simpleNameOf
import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.ksp.logging.KspLogger
import kotlin.reflect.KProperty1

class KspParser(private val resolver: Resolver, private val logger: KspLogger) {

    fun parseNodes(): Sequence<KMixinNode<KSFile>> =
        resolver
            .getSymbolsWithAnnotation(qualifiedNameOf<KMixin>())
            .filterIsInstance<KSClassDeclaration>()
            .map { it.parseAsRoot() }

    private fun KSClassDeclaration.parseAsRoot(): KMixinNode<KSFile> {
        val allFunctionDeclarations = getDeclaredFunctions()
        val constructorDeclarations = allFunctionDeclarations.filter { it.isConstructor() }
        val primaryConstructorPropertyNames = constructorDeclarations.flatMap { constructor ->
            constructor.parameters.mapNotNull { parameter -> parameter.takeIf { it.isVal || it.isVar }?.name?.toName() }
        }.toSet()
        val propertyDeclarations = getDeclaredProperties().filter {
            it.simpleName.toName() !in primaryConstructorPropertyNames
        }
        val functionDeclarations = allFunctionDeclarations.filter { !it.isConstructor() }
        return KMixinNode(
            name = simpleName.toName(),
            type = asStarProjectedType().parse(),
            isClass = classKind == ClassKind.CLASS,
            isInterface = classKind == ClassKind.INTERFACE,
            isOpen = Modifier.OPEN in modifiers,
            isAbstract = Modifier.ABSTRACT in modifiers,
            isSealed = Modifier.SEALED in modifiers,
            isTopLevel = parentDeclaration == null,
            isPublic = isPublic(),
            constructors = constructorDeclarations.map { it.parseAsConstructor() }.toList(),
            properties = propertyDeclarations.map { it.parse() }.toList(),
            functions = functionDeclarations.map { it.parseAsFunction() }.toList(),
            companionObject = declarations.filterIsInstance<KSClassDeclaration>().find { it.isCompanionObject }
                ?.parseAsCompanionObject(),
            annotations = annotations.parse(),
            typeParameters = typeParameters.map { it.parse() },
            origin = containingFile,
            node = toNode(),
        )
    }

    private fun KSFunctionDeclaration.parseAsConstructor() = KMixinNode.Constructor(
        isPublic = isPublic(),
        parameters = parameters.map { it.parseAsConstructorParameter() },
        node = toNode(),
    )

    private fun KSValueParameter.parseAsConstructorParameter() = KMixinNode.Constructor.Parameter(
        name = name?.toName(),
        type = type.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    @OptIn(KspExperimental::class)
    private fun KSPropertyDeclaration.parse() = KMixinNode.Property(
        name = simpleName.toName(),
        type = type.parse(),
        isPublic = isPublic(),
        isOpen = Modifier.OPEN in modifiers,
        isAbstract = Modifier.ABSTRACT in modifiers,
        hasExtensionReceiver = extensionReceiver != null,
        getter = getter?.let {
            KMixinNode.Property.Getter(
                jvmName = resolver.getJvmName(it),
                annotations = annotations.parse(),
            )
        },
        setter = setter?.takeUnless { Modifier.PRIVATE in it.modifiers }?.let {
            KMixinNode.Property.Setter(
                jvmName = resolver.getJvmName(it),
            )
        },
        contextParameters = contextParameters.map { it.parse() },
        typeParameters = typeParameters.map { it.parse() },
        annotations = annotations.parse(),
        node = toNode(),
    )

    @OptIn(KspExperimental::class)
    private fun KSFunctionDeclaration.parseAsFunction() = KMixinNode.Function(
        name = simpleName.toName(),
        jvmName = resolver.getJvmName(this),
        parameters = parameters.map { it.parseAsFunctionParameter() },
        contextParameters = contextParameters.map { it.parse() },
        returnType = returnType?.parse() ?: ParsedInvalidType,
        isPublic = isPublic(),
        isOpen = Modifier.OPEN in modifiers,
        isSuspending = Modifier.SUSPEND in modifiers,
        isAbstract = isAbstract,
        extensionReceiverType = extensionReceiver?.parse(),
        typeParameters = typeParameters.map { it.parse() },
        annotations = annotations.parse(),
        node = toNode(),
    )

    private fun KSValueParameter.parseAsFunctionParameter() = KMixinNode.Function.Parameter(
        name = name?.toName(),
        type = type.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    private fun KSContextParameter.parse() = ContextParameterNode(
        name = name?.toName(),
        type = type.parse(),
        annotations = annotations.parse(),
        node = toNode(),
    )

    private fun KSClassDeclaration.parseAsCompanionObject() = KMixinNode.CompanionObject(
        name = simpleName.toName(),
        isPublic = isPublic(),
        functions = getDeclaredFunctions().filter { !it.isConstructor() }.map { it.parseAsFunction() }.toList(),
        node = toNode(),
    )

    private fun Sequence<KSAnnotation>.parse(): AnnotationsContainer =
        AnnotationsContainer.of(map { it.parse() }.toList())

    private fun KSAnnotation.parse() = AnnotationNode(
        type = annotationType.parse(),
        arguments = arguments.map { it.parseAsAnnotationArgument() },
        node = toNode(),
    )

    private fun KSValueArgument.parseAsAnnotationArgument(): AnnotationNode.Argument {
        val value = value
        val name = name?.toName()
        val isExplicit = origin != KspOrigin.SYNTHETIC
        val node = toNode()
        val arrayValues = when (value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (arrayValues != null) {
            return AnnotationNode.ArrayArgument(
                name = name,
                isExplicit = isExplicit,
                elements = arrayValues.map { it.parseAsAnnotationArgumentValue() },
                node = node,
            )
        }
        return AnnotationNode.ScalarArgument(
            name = name,
            isExplicit = isExplicit,
            value = value.parseAsAnnotationArgumentValue(),
            node = node,
        )
    }

    private fun Any?.parseAsAnnotationArgumentValue(): AnnotationNode.Argument.Value = when (val value = this) {
        null -> AnnotationNode.Argument.InvalidValue
        is Boolean -> AnnotationNode.Argument.BooleanValue(value)
        is Byte -> AnnotationNode.Argument.ByteValue(value)
        is Short -> AnnotationNode.Argument.ShortValue(value)
        is Int -> AnnotationNode.Argument.IntValue(value)
        is Long -> AnnotationNode.Argument.LongValue(value)
        is Char -> AnnotationNode.Argument.CharValue(value)
        is Float -> AnnotationNode.Argument.FloatValue(value)
        is Double -> AnnotationNode.Argument.DoubleValue(value)
        is String -> AnnotationNode.Argument.StringValue(value)
        is KSType -> AnnotationNode.Argument.TypeValue(value.parse())
        is KSClassDeclaration -> {
            val parentClassDeclaration = value.parentDeclaration as? KSClassDeclaration
                ?: internalError("Failed to resolve enclosing enum class of entry: '${value.qualifiedName}'.")
            AnnotationNode.Argument.EnumValue(
                type = parentClassDeclaration.asStarProjectedType().parse(),
                name = value.simpleName.toName(),
            )
        }

        is KSAnnotation -> AnnotationNode.Argument.AnnotationValue(value.parse())
        else -> internalError("Unexpected type of annotation argument value: '${value::class.qualifiedName}'.")
    }

    private fun KSType.parse(): ParsedType {
        if (isError) return ParsedInvalidType
        val ksDeclaration = declaration
        if (ksDeclaration is KSTypeParameter) {
            return ParsedTypeArgument(name = ksDeclaration.name.toName(), type = toType())
        }
        val actualType = if (ksDeclaration is KSTypeAlias) {
            val unwrapped = unwrapActualType(ksDeclaration)?.parse() as? ParsedClassType ?: return ParsedInvalidType
            unwrapped.actualOrThis
        } else null
        val isSuspendFunctionType = isSuspendFunctionType
        val isFunctional = isFunctionType || isSuspendFunctionType
        val functionalTypeArguments = mutableListOf<FunctionalTypeArgument>()
        val parsedArguments = arguments.map { ksArgument ->
            val kspVariance = ksArgument.variance
            if (kspVariance == KspVariance.STAR) {
                if (isFunctional) return ParsedInvalidType
                ParsedClassType.StarProjectionArgument
            } else {
                val type = ksArgument.type?.parse() as? ParsedValidType ?: return ParsedInvalidType
                val variance = when (kspVariance) {
                    KspVariance.INVARIANT -> Variance.INVARIANT
                    KspVariance.COVARIANT -> Variance.COVARIANT
                    KspVariance.CONTRAVARIANT -> Variance.CONTRAVARIANT
                }
                if (isFunctional) {
                    functionalTypeArguments += FunctionalTypeArgument(type, ksArgument.annotations)
                }
                ParsedClassType.GenericTypeArgument(type, variance)
            }
        }
        val functionalType = if (isFunctional) {
            parseFunctionalType(annotations, functionalTypeArguments, isSuspendFunctionType) ?: return ParsedInvalidType
        } else null
        return ParsedClassType(
            packageName = ksDeclaration.packageName.toName(),
            qualifiedName = ksDeclaration.qualifiedName?.toName(),
            arguments = parsedArguments,
            actualType = actualType,
            functionalType = functionalType,
            typeParameters = ksDeclaration.typeParameters.map { it.parse() },
            type = toType(),
        )
    }

    private fun KSType.unwrapActualType(alias: KSTypeAlias, visited: Set<KSTypeAlias> = emptySet()): KSType? {
        if (alias in visited) return null
        if (alias.typeParameters.size != arguments.size) return null
        val args = alias.typeParameters.zip(arguments) { parameter, argument ->
            parameter.name.toName() to argument
        }.toMap()
        return alias.type.resolve().substituteTypeAlias(args = args, visited = visited + alias)
    }

    private fun KSType.substituteTypeAlias(args: Map<String, KSTypeArgument>, visited: Set<KSTypeAlias>): KSType? {
        if (arguments.isNotEmpty()) {
            return replace(arguments.map { argument ->
                if (argument.variance == KspVariance.STAR) return@map argument
                val type = argument.type?.resolve() ?: return null
                val substitutedType = type.substituteTypeAlias(args, visited) ?: return null
                resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(substitutedType), argument.variance)
            })
        }
        val unfoldedType = if (declaration is KSTypeParameter) {
            args[declaration.simpleName.toName()]?.type?.resolve()?.preserveNullableFrom(this) ?: return this
        } else {
            this
        }
        val alias = unfoldedType.declaration as? KSTypeAlias ?: return unfoldedType
        return unfoldedType.unwrapActualType(alias, visited)
    }

    private data class FunctionalTypeArgument(val type: ParsedValidType, val ksAnnotations: Sequence<KSAnnotation>)

    private fun parseFunctionalType(
        ksAnnotations: Sequence<KSAnnotation>,
        arguments: List<FunctionalTypeArgument>,
        isSuspending: Boolean,
    ): ParsedClassType.FunctionalType? {
        val rawArguments = arguments.toMutableList()
        val contextTypes = List(ksAnnotations.findArgument(ContextFunctionTypeParams::count) ?: 0) {
            rawArguments.removeFirstOrNull()?.type ?: return null
        }
        val receiverType = if (ksAnnotations.hasAnnotation<ExtensionFunctionType>()) {
            rawArguments.removeFirstOrNull()?.type ?: return null
        } else null
        val returnType = rawArguments.removeLastOrNull()?.type ?: return null
        val parameters = rawArguments.map { rawArgument ->
            ParsedClassType.FunctionalType.Parameter(
                name = rawArgument.ksAnnotations.findArgument(ParameterName::name),
                type = rawArgument.type,
            )
        }
        return ParsedClassType.FunctionalType(
            contextTypes = contextTypes,
            receiverType = receiverType,
            parameters = parameters,
            returnType = returnType,
            isSuspending = isSuspending,
        )
    }

    private fun KSTypeReference.parse(): ParsedType = resolve().parse()

    private fun KSTypeParameter.parse() = TypeParameterNode(
        name = name.toName(),
        bounds = bounds.map { it.parse() }.toList(),
        isReified = isReified,
        annotations = annotations.parse(),
        node = toNode(),
    )

    private inline fun <reified A : Annotation> Sequence<KSAnnotation>.findAnnotation(): KSAnnotation? {
        val expectedShortName = simpleNameOf<A>()
        val expectedQualifiedName = qualifiedNameOf<A>()
        return find { annotation ->
            val type = annotation.annotationType
            if (annotation.shortName.toName() != expectedShortName) return@find false
            if (type.validate(enableNewFeatures = true)) {
                type.resolve().declaration.qualifiedName?.toName() == expectedQualifiedName
            } else {
                type.toString() == "<ERROR TYPE: $expectedQualifiedName>"
            }
        }
    }

    private inline fun <reified A : Annotation> Sequence<KSAnnotation>.hasAnnotation(): Boolean =
        findAnnotation<A>() != null

    private inline fun <reified A : Annotation, reified V> Sequence<KSAnnotation>.findArgument(
        property: KProperty1<out A, V>
    ): V? = findAnnotation<A>()?.arguments?.find { it.name?.toName() == property.name }?.value as? V

    private fun KSNode.toNode(): KspNode = KspNode(this, logger)
    private fun KSType.toType(): KspType = KspType(this)
    private fun KSName.toName(): String = asString()

    private typealias KspOrigin = com.google.devtools.ksp.symbol.Origin
    private typealias KspVariance = com.google.devtools.ksp.symbol.Variance
}

private fun KSType.preserveNullableFrom(source: KSType): KSType =
    if (source.isMarkedNullable && !isMarkedNullable) makeNullable() else this
