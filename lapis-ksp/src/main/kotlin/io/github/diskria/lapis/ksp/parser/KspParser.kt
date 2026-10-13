package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.core.extensions.lapisError
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.extensions.simpleNameOf
import io.github.diskria.lapis.core.parser.models.*
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.ksp.logging.KspLogger
import kotlin.reflect.KProperty1

class KspParser(private val resolver: Resolver, private val logger: KspLogger) {

    fun parseNodes(): Sequence<KMixinNode<KSFile>> =
        resolver
            .getSymbolsWithAnnotation(AnnotationsContainer.ROOT_FQCN)
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
        arguments = arguments.mapNotNull { argument ->
            if (argument.origin == KspOrigin.SYNTHETIC) return@mapNotNull null
            val name = argument.name?.toName() ?: return@mapNotNull AnnotationNode.InvalidArgument(argument.toNode())
            argument.parseAsAnnotationArgument(name)
        },
        node = toNode(),
    )

    private fun KSValueArgument.parseAsAnnotationArgument(name: String): AnnotationNode.Argument {
        val value = value
        val arrayValues = when (value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (arrayValues != null) {
            return AnnotationNode.ArrayArgument(
                name = name,
                elements = arrayValues.map { it.parseAsAnnotationArgumentValue() },
                node = toNode(),
            )
        }
        return AnnotationNode.ScalarArgument(
            name = name,
            value = value.parseAsAnnotationArgumentValue(),
            node = toNode(),
        )
    }

    private fun Any?.parseAsAnnotationArgumentValue(): AnnotationNode.Argument.Value = when (val rawValue = this) {
        null -> AnnotationNode.Argument.InvalidValue
        is Boolean -> AnnotationNode.Argument.BooleanValue(rawValue)
        is Byte -> AnnotationNode.Argument.ByteValue(rawValue)
        is Short -> AnnotationNode.Argument.ShortValue(rawValue)
        is Int -> AnnotationNode.Argument.IntValue(rawValue)
        is Long -> AnnotationNode.Argument.LongValue(rawValue)
        is Char -> AnnotationNode.Argument.CharValue(rawValue)
        is Float -> AnnotationNode.Argument.FloatValue(rawValue)
        is Double -> AnnotationNode.Argument.DoubleValue(rawValue)
        is String -> AnnotationNode.Argument.StringValue(rawValue)
        is KSType -> AnnotationNode.Argument.TypeValue(rawValue.parse())
        is KSClassDeclaration -> {
            val parentClassDeclaration = rawValue.parentDeclaration as? KSClassDeclaration
                ?: lapisError("Failed to resolve enclosing enum class of entry: '${rawValue.qualifiedName}'.")
            AnnotationNode.Argument.EnumValue(
                type = parentClassDeclaration.asStarProjectedType().parse(),
                name = rawValue.simpleName.toName(),
            )
        }

        is KSAnnotation -> AnnotationNode.Argument.AnnotationValue(rawValue.parse())
        else -> lapisError("Unexpected value for annotation argument: '${rawValue::class.qualifiedName}'.")
    }

    private fun KSType.parse(): ParsedType {
        if (isError) return ParsedInvalidType
        val ksDeclaration = declaration
        if (ksDeclaration is KSTypeParameter) {
            return ParsedTypeParameterType(ksDeclaration.name.toName(), toType())
        }
        val actualType = if (ksDeclaration is KSTypeAlias) {
            val expanded = fullyExpandedType(ksDeclaration)
            if (expanded === this) return ParsedInvalidType
            val parsedExpanded = expanded.parse() as? ParsedClassType ?: return ParsedInvalidType
            parsedExpanded.actualOrThis
        } else null
        val ksTypeArguments = arguments
        val parsedArguments = ksTypeArguments.map { ksArgument ->
            when (val kspVariance = ksArgument.variance) {
                KspVariance.STAR -> ParsedClassType.StarProjectionArgument
                else -> {
                    val type = ksArgument.type?.parse() as? ParsedValidType ?: return ParsedInvalidType
                    val variance = when (kspVariance) {
                        KspVariance.CONTRAVARIANT -> Variance.CONTRAVARIANT
                        KspVariance.COVARIANT -> Variance.COVARIANT
                        KspVariance.INVARIANT -> Variance.INVARIANT
                    }
                    ParsedClassType.TypeProjectionArgument(type, variance)
                }
            }
        }
        val isSuspendFunctionType = isSuspendFunctionType
        val isFunctional = isFunctionType || isSuspendFunctionType
        val functionalType = if (isFunctional) {
            parseFunctionalType(ksTypeArguments, isSuspendFunctionType) ?: return ParsedInvalidType
        } else null
        return ParsedClassType(
            packageName = ksDeclaration.packageName.toName(),
            qualifiedName = ksDeclaration.qualifiedName?.toName(),
            typeArguments = parsedArguments,
            actualType = actualType,
            functionalType = functionalType,
            type = toType(),
        )
    }

    private fun KSType.fullyExpandedType(alias: KSTypeAlias): KSType =
        directExpansionType(alias, visited = emptySet()) ?: this

    private fun KSType.directExpansionType(ksTypeAlias: KSTypeAlias, visited: Set<KSTypeAlias>): KSType? {
        if (ksTypeAlias in visited) return null
        if (ksTypeAlias.typeParameters.size != arguments.size) return null
        val ksTypeArguments = ksTypeAlias.typeParameters.zip(arguments) { ksTypeParameter, ksTypeArgument ->
            ksTypeParameter.name.toName() to ksTypeArgument
        }.toMap()
        val mapped = ksTypeAlias.type.resolve().mapTypeAliasArguments(ksTypeArguments, visited + ksTypeAlias)
        return mapped?.applyNullabilityFrom(this)
    }

    private fun KSType.mapTypeAliasArguments(
        ksTypeArguments: Map<String, KSTypeArgument>,
        visited: Set<KSTypeAlias> = emptySet(),
    ): KSType? {
        val ksDeclaration = declaration
        val expanded = if (ksDeclaration is KSTypeParameter) {
            val ksTypeArgument = ksTypeArguments[ksDeclaration.name.toName()]
            if (ksTypeArgument != null) {
                ksTypeArgument.type?.resolve()?.applyNullabilityFrom(this) ?: return null
            } else {
                this
            }
        } else {
            this
        }
        val substituted = if (expanded.arguments.isNotEmpty()) {
            expanded.replace(expanded.arguments.map { argument ->
                val kspVariance = argument.variance
                if (kspVariance == KspVariance.STAR) return@map argument
                val type = argument.type?.resolve() ?: return null
                val mapped = type.mapTypeAliasArguments(ksTypeArguments, visited) ?: return null
                val ksTypeArgument = (type.declaration as? KSTypeParameter)?.let { ksTypeArguments[it.name.toName()] }
                val variance = if (ksTypeArgument != null) {
                    combineVariance(ksTypeArgument.variance, kspVariance) ?: return null
                } else {
                    kspVariance
                }
                resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(mapped), variance)
            })
        } else {
            expanded
        }
        val ksTypeAlias = substituted.declaration as? KSTypeAlias ?: return substituted
        return substituted.directExpansionType(ksTypeAlias, visited)
    }

    private fun combineVariance(callSite: KspVariance, declarationSite: KspVariance): KspVariance? {
        if (callSite == declarationSite) return callSite
        if (callSite == KspVariance.STAR || declarationSite == KspVariance.STAR) return KspVariance.STAR
        if (callSite == KspVariance.INVARIANT) return declarationSite
        if (declarationSite == KspVariance.INVARIANT) return callSite
        return null
    }

    private fun KSType.applyNullabilityFrom(abbreviation: KSType): KSType =
        if (abbreviation.isMarkedNullable && !isMarkedNullable) makeNullable() else this

    private fun KSType.parseFunctionalType(
        ksTypeArguments: List<KSTypeArgument>,
        isSuspending: Boolean,
    ) = ParsedClassType.FunctionalType.parse(
        typeArguments = ksTypeArguments,
        isSuspending = isSuspending,
        nothingType = resolver.builtIns.nothingType,
        nullableAnyType = resolver.builtIns.anyType.makeNullable(),
        getEffectiveType = { ksTypeArgument, fallbackKSType ->
            val ksType = ksTypeArgument.type
            when {
                ksTypeArgument.variance == KspVariance.STAR || ksType == null -> fallbackKSType
                else -> ksType.resolve()
            }
        },
        parseType = { type -> type.parse() },
        getContextParametersCount = { annotations.findArgument(ContextFunctionTypeParams::count) ?: 0 },
        isExtension = { annotations.hasAnnotation<ExtensionFunctionType>() },
        getParameterName = { argument, _ -> argument.annotations.findArgument(ParameterName::name) },
    )

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
