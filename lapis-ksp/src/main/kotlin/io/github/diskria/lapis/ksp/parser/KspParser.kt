package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.ContextParams
import io.github.diskria.lapis.annotations.KMixin
import io.github.diskria.lapis.core.extensions.internalError
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.extensions.simpleNameOf
import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.ksp.KspLogger
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
            it.simpleName.asString() !in primaryConstructorPropertyNames
        }
        val functionDeclarations = allFunctionDeclarations.filter { !it.isConstructor() }
        return KMixinNode(
            name = simpleName.toName(),
            type = asStarProjectedType().parse(this),
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
            annotations = parseAnnotations(),
            typeParameters = typeParameters.parse(),
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
        annotations = parseAnnotations(),
        node = toNode(),
    )

    @OptIn(KspExperimental::class)
    private fun KSPropertyDeclaration.parse(): KMixinNode.Property {
        // TODO: Clean up the @ContextParams workaround
        val contextParameters = if (contextParameters.isNotEmpty()) {
            logger.warn(
                "KSP now supports context parameters. Time to clean up the @ContextParams workaround!", this
            )
            contextParameters.map { it.parse() }
        } else {
            val contextParamsCount = annotations.findArgument(ContextParams::count) ?: 0
            if (contextParamsCount < 0) {
                logger.error("ContextParams count ($contextParamsCount) cannot be negative.", this)
                emptyList()
            } else {
                List(contextParamsCount) { index ->
                    ContextParameterNode(
                        name = "ctx$index",
                        type = type.parse(),
                        annotations = type.parseAnnotations(),
                        node = toNode(),
                    )
                }
            }
        }
        return KMixinNode.Property(
            name = simpleName.toName(),
            type = type.parse(),
            isPublic = isPublic(),
            isOpen = Modifier.OPEN in modifiers,
            isAbstract = Modifier.ABSTRACT in modifiers,
            hasExtensionReceiver = extensionReceiver != null,
            getter = getter?.let {
                KMixinNode.Property.Getter(
                    jvmName = resolver.getJvmName(it),
                    annotations = parseAnnotations(),
                )
            },
            setter = setter?.takeUnless { Modifier.PRIVATE in it.modifiers }?.let {
                KMixinNode.Property.Setter(
                    jvmName = resolver.getJvmName(it),
                )
            },
            contextParameters = contextParameters,
            typeParameters = typeParameters.parse(),
            annotations = parseAnnotations(),
            node = toNode(),
        )
    }

    @OptIn(KspExperimental::class)
    private fun KSFunctionDeclaration.parseAsFunction(): KMixinNode.Function {
        // TODO: Clean up the @ContextParams workaround
        val (parameters, contextParameters) = if (contextParameters.isNotEmpty()) {
            logger.warn(
                "KSP now supports context parameters. Time to clean up the @ContextParams workaround!", this
            )
            parameters.map { it.parseAsFunctionParameter() } to contextParameters.map { it.parse() }
        } else {
            val contextParamsCount = annotations.findArgument(ContextParams::count) ?: 0
            if (contextParamsCount !in 0..parameters.size) {
                logger.error("ContextParams count ($contextParamsCount) is out of range [0, ${parameters.size}].", this)
                emptyList<KMixinNode.Function.Parameter>() to emptyList()
            } else if (contextParamsCount > 0) {
                val syntheticContexts = parameters.take(contextParamsCount).map { parameter ->
                    ContextParameterNode(
                        name = parameter.name?.toName(),
                        type = parameter.type.parse(),
                        annotations = parameter.parseAnnotations(),
                        node = parameter.toNode(),
                    )
                }
                val remainingParameters = parameters.drop(contextParamsCount).map { it.parseAsFunctionParameter() }
                remainingParameters to syntheticContexts
            } else {
                parameters.map { it.parseAsFunctionParameter() } to emptyList()
            }
        }
        return KMixinNode.Function(
            name = simpleName.toName(),
            jvmName = resolver.getJvmName(this),
            parameters = parameters,
            contextParameters = contextParameters,
            returnType = returnType.parse(this),
            isPublic = isPublic(),
            isOpen = Modifier.OPEN in modifiers,
            isSuspending = Modifier.SUSPEND in modifiers,
            isAbstract = isAbstract,
            extensionReceiverType = extensionReceiver?.parse(),
            typeParameters = typeParameters.parse(),
            annotations = parseAnnotations(),
            node = toNode(),
        )
    }

    private fun KSValueParameter.parseAsFunctionParameter() = KMixinNode.Function.Parameter(
        name = name?.toName(),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = toNode(),
    )

    private fun KSContextParameter.parse() = ContextParameterNode(
        name = name?.toName(),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = toNode(),
    )

    private fun KSClassDeclaration.parseAsCompanionObject() = KMixinNode.CompanionObject(
        name = simpleName.toName(),
        isPublic = isPublic(),
        functions = getDeclaredFunctions().filter { !it.isConstructor() }.map { it.parseAsFunction() }.toList(),
        node = toNode(),
    )

    private fun KSAnnotated.parseAnnotations(): AnnotationsContainer =
        AnnotationsContainer.of(annotations.map { it.parse() }.toList())

    private fun KSAnnotation.parse(): AnnotationNode {
        val type = annotationType.parse() as? ValidTypeNode ?: return InvalidAnnotationNode(toNode())
        return ValidAnnotationNode(
            type = type,
            arguments = arguments.map { it.parse() },
            node = toNode(),
        )
    }

    private fun KSValueArgument.parse(): AnnotationNode.Argument {
        val rawValues = when (val value = value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (rawValues != null) {
            val elements = rawValues.map { rawValue ->
                rawValue ?: return AnnotationNode.InvalidArgument(toNode())
                parseValue(rawValue)
            }
            return AnnotationNode.ArrayArgument(
                name = name?.toName(),
                isExplicit = origin != Origin.SYNTHETIC,
                elements = elements,
                node = toNode(),
            )
        }
        val rawValue = value ?: return AnnotationNode.InvalidArgument(toNode())
        return AnnotationNode.ScalarArgument(
            name = name?.toName(),
            isExplicit = origin != Origin.SYNTHETIC,
            value = parseValue(rawValue),
            node = toNode(),
        )
    }

    private fun KSValueArgument.parseValue(raw: Any): AnnotationNode.Argument.Value = when (raw) {
        is Boolean -> AnnotationNode.Argument.BooleanValue(raw)
        is Byte -> AnnotationNode.Argument.ByteValue(raw)
        is Short -> AnnotationNode.Argument.ShortValue(raw)
        is Int -> AnnotationNode.Argument.IntValue(raw)
        is Long -> AnnotationNode.Argument.LongValue(raw)
        is Char -> AnnotationNode.Argument.CharValue(raw)
        is Float -> AnnotationNode.Argument.FloatValue(raw)
        is Double -> AnnotationNode.Argument.DoubleValue(raw)
        is String -> AnnotationNode.Argument.StringValue(raw)
        is KSType -> AnnotationNode.Argument.TypeValue(type = raw.parse(this), node = toNode())
        is KSClassDeclaration -> {
            val parentClassDeclaration = raw.parentDeclaration as? KSClassDeclaration
                ?: internalError("Failed to resolve enclosing enum class of entry: '${raw.qualifiedName}'.")
            AnnotationNode.Argument.EnumValue(
                type = parentClassDeclaration.asStarProjectedType().parse(this),
                name = raw.simpleName.toName(),
                node = toNode(),
            )
        }

        is KSAnnotation -> AnnotationNode.Argument.AnnotationValue(raw.parse())
        else -> internalError("Unexpected type of annotation argument value: '${raw::class.qualifiedName}'.")
    }

    private fun KSType.parse(siteNode: KSNode): TypeNode {
        if (isError) return InvalidTypeNode(node = siteNode.toNode())
        val declaration = declaration
        if (declaration is KSTypeParameter) {
            return if (arguments.isNotEmpty()) {
                InvalidTypeNode(node = siteNode.toNode())
            } else {
                TypeArgumentNode(
                    name = declaration.name.toName(),
                    type = toType(),
                    isNullable = isMarkedNullable,
                    node = siteNode.toNode(),
                )
            }
        }
        val canonicalReference = if (declaration is KSTypeAlias) {
            val expanded = expandTypealias()?.parse(siteNode) as? ClassTypeNode
                ?: return InvalidTypeNode(siteNode.toNode())
            expanded.canonicalType ?: expanded
        } else null
        if (declaration !is KSClassDeclaration && declaration !is KSTypeAlias) return InvalidTypeNode(siteNode.toNode())
        val functionalType = if (isFunctionType || isSuspendFunctionType) {
            val parameters = arguments.toMutableList()
            val contextParametersCount = annotations.findArgument(ContextFunctionTypeParams::count) ?: 0
            val effectiveContextParametersCount = if (contextParametersCount > 0) {
                logger.warn(
                    "KSP now supports context parameters. Time to clean up the @ContextParams workaround!", siteNode
                )
                contextParametersCount
            } else {
                annotations.findArgument(ContextParams::count) ?: 0
            }
            val contextTypes = List(effectiveContextParametersCount) {
                parameters.removeFirstOrNull()?.type?.parse(siteNode) ?: return InvalidTypeNode(siteNode.toNode())
            }
            val receiverType = if (annotations.hasAnnotation<ExtensionFunctionType>()) {
                parameters.removeFirstOrNull()?.type?.parse(siteNode) ?: return InvalidTypeNode(siteNode.toNode())
            } else null
            val returnType = parameters.removeLastOrNull()?.type?.parse(siteNode)
                ?: return InvalidTypeNode(siteNode.toNode())
            ClassTypeNode.FunctionalType(
                contextTypes = contextTypes,
                receiverType = receiverType,
                parameters = parameters.map { parameter ->
                    val typeReference = parameter.type
                    ClassTypeNode.FunctionalType.Parameter(
                        name = typeReference?.annotations?.findArgument(ParameterName::name),
                        type = typeReference?.parse(siteNode) ?: return InvalidTypeNode(siteNode.toNode())
                    )
                },
                returnType = returnType,
                isSuspending = isSuspendFunctionType,
            )
        } else null
        return ClassTypeNode(
            packageName = declaration.packageName.toName(),
            qualifiedName = declaration.qualifiedName?.toName(),
            arguments = arguments.map { argument ->
                val kspVariance = argument.variance
                if (kspVariance == KspVariance.STAR) {
                    ClassTypeNode.StarProjectionArgument(siteNode.toNode())
                } else {
                    val type = argument.type.parse(siteNode)
                    val variance = when (kspVariance) {
                        KspVariance.INVARIANT -> Variance.INVARIANT
                        KspVariance.COVARIANT -> Variance.COVARIANT
                        KspVariance.CONTRAVARIANT -> Variance.CONTRAVARIANT
                    }
                    ClassTypeNode.GenericTypeArgument(type, variance, siteNode.toNode())
                }
            },
            canonicalType = canonicalReference,
            functionalType = functionalType,
            type = toType(),
            isNullable = isMarkedNullable,
            node = siteNode.toNode(),
        )
    }

    private fun KSType.expandTypealias(visited: Set<KSTypeAlias> = emptySet()): KSType? {
        val alias = declaration as? KSTypeAlias ?: return this
        if (alias in visited) return null
        val substitutions = alias.typeParameters.map { it.name.toName() }.zip(arguments).toMap()
        return alias.type.resolve().substituteTypeParameters(substitutions, visited + alias)
    }

    private fun KSType.substituteTypeParameters(
        substitutions: Map<String, KSTypeArgument>,
        visitedAliases: Set<KSTypeAlias>
    ): KSType? {
        if (declaration is KSTypeParameter) {
            val argument = substitutions[declaration.simpleName.toName()] ?: return this
            val argumentType = argument.type?.resolve() ?: return this
            val resolvedType = if (isMarkedNullable) argumentType.makeNullable() else argumentType
            return resolvedType.expandTypealias(visitedAliases)
        }
        if (arguments.isNotEmpty()) {
            return replace(arguments.map { argument ->
                val type = argument.type?.resolve() ?: return@map argument
                val substitutedType = type.substituteTypeParameters(substitutions, visitedAliases) ?: return null
                resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(substitutedType), argument.variance)
            })
        }
        return expandTypealias(visitedAliases)
    }

    private fun KSTypeReference.parse(): TypeNode =
        resolve().parse(this)

    private fun KSTypeReference?.parse(viewNode: KSNode): TypeNode =
        this?.parse() ?: InvalidTypeNode(viewNode.toNode())

    @JvmName("parseTypeParameters")
    private fun List<KSTypeParameter>.parse() = map { typeParameter ->
        TypeParameterNode(
            name = typeParameter.name.toName(),
            bounds = typeParameter.bounds.map { it.parse() }.toList(),
            isReified = typeParameter.isReified,
            node = typeParameter.toNode(),
        )
    }

    private fun KSNode.toNode(): KspNode = KspNode(this, logger)
    private fun KSType.toType(): KspType = KspType(this)
    private fun KSName.toName(): String = asString()
}

private inline fun <reified A : Annotation> Sequence<KSAnnotation>.findAnnotation(): KSAnnotation? {
    val expectedShortName = simpleNameOf<A>()
    val expectedQualifiedName = qualifiedNameOf<A>()
    return find { annotation ->
        val annotationType = annotation.annotationType
        if (annotation.shortName.asString() != expectedShortName) return@find false
        if (annotationType.validate(enableNewFeatures = true)) {
            annotationType.resolve().declaration.qualifiedName?.asString() == expectedQualifiedName
        } else {
            annotationType.toString() == "<ERROR TYPE: $expectedQualifiedName>"
        }
    }
}

private inline fun <reified A : Annotation> Sequence<KSAnnotation>.hasAnnotation(): Boolean =
    findAnnotation<A>() != null

private inline fun <reified A : Annotation, reified V> Sequence<KSAnnotation>.findArgument(
    property: KProperty1<out A, V>
): V? = findAnnotation<A>()?.arguments?.find { it.name?.asString() == property.name }?.value as? V

private typealias KspVariance = com.google.devtools.ksp.symbol.Variance
