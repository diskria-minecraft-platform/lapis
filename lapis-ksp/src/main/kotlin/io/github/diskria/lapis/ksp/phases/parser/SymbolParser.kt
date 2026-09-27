package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.KMixin
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.extensions.isSubpackageOf
import io.github.diskria.lapis.ksp.extensions.qualifiedNameOf
import io.github.diskria.lapis.ksp.phases.parser.models.*

class SymbolParser(private val resolver: Resolver) {

    fun parseNodes(): Sequence<KMixinNode> =
        resolver
            .getSymbolsWithAnnotation(qualifiedNameOf<KMixin>())
            .filterIsInstance<KSClassDeclaration>()
            .map { it.parseAsRoot() }

    private fun KSClassDeclaration.parseAsRoot(): KMixinNode {
        val allFunctionDeclarations = getDeclaredFunctions()
        val constructorDeclarations = allFunctionDeclarations.filter { it.isConstructor() }
        val primaryConstructorPropertyNames = constructorDeclarations.flatMap { constructor ->
            constructor.parameters.mapNotNull {
                if (it.isVal || it.isVar) (it.name.parse(it) as? ValidNameNode)?.name
                else null
            }
        }.toSet()
        val propertyDeclarations = getDeclaredProperties().filter {
            it.simpleName.asString() !in primaryConstructorPropertyNames
        }
        val functionDeclarations = allFunctionDeclarations.filter { !it.isConstructor() }
        return KMixinNode(
            name = simpleName.parse(this),
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
            node = this,
        )
    }

    private fun KSFunctionDeclaration.parseAsConstructor() = KMixinNode.Constructor(
        isPublic = isPublic(),
        parameters = parameters.map { it.parseAsConstructorParameter() },
        node = this,
    )

    private fun KSValueParameter.parseAsConstructorParameter() = KMixinNode.Constructor.Parameter(
        name = name.parse(this),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    @OptIn(KspExperimental::class)
    private fun KSPropertyDeclaration.parse() = KMixinNode.Property(
        name = simpleName.parse(type),
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
        setter = setter?.takeIf { Modifier.PUBLIC in it.modifiers }?.let {
            KMixinNode.Property.Setter(
                jvmName = resolver.getJvmName(it),
            )
        },
        typeParameters = typeParameters.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    @OptIn(KspExperimental::class)
    private fun KSFunctionDeclaration.parseAsFunction() = KMixinNode.Function(
        name = simpleName.parse(this),
        jvmName = resolver.getJvmName(this),
        parameters = parameters.map { it.parseAsFunctionParameter() },
        returnType = returnType.parse(this).takeIf {
            if (it !is ValidTypeNode) return@takeIf true
            (it.canonicalType?.ksType ?: it.ksType).makeNotNullable() != resolver.builtIns.unitType
        },
        isPublic = isPublic(),
        isOpen = Modifier.OPEN in modifiers,
        isAbstract = isAbstract,
        extensionReceiverType = extensionReceiver?.parse(),
        typeParameters = typeParameters.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    private fun KSValueParameter.parseAsFunctionParameter() = KMixinNode.Function.Parameter(
        name = name.parse(this),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    private fun KSClassDeclaration.parseAsCompanionObject() = KMixinNode.CompanionObject(
        name = simpleName.parse(this),
        isPublic = isPublic(),
        functions = getDeclaredFunctions().filter { !it.isConstructor() }.map { it.parseAsFunction() }.toList(),
        node = this,
    )

    private fun KSAnnotated.parseAnnotations(): AnnotationNodeContainer {
        val api = mutableListOf<ValidAnnotationNode>()
        val external = mutableListOf<AnnotationNode>()
        annotations.forEach {
            val annotation = it.parse()
            if (annotation is ValidAnnotationNode &&
                annotation.type.packageName?.isSubpackageOf(API_ANNOTATIONS_PACKAGE) == true
            ) {
                api += annotation
            } else {
                external += annotation
            }
        }
        return AnnotationNodeContainer(api, external)
    }

    private fun KSAnnotation.parse(): AnnotationNode {
        val type = annotationType.parse() as? ValidTypeNode ?: return InvalidAnnotationNode(this)
        return ValidAnnotationNode(
            type = type,
            arguments = arguments.map { it.parse() },
            node = this,
        )
    }

    private fun KSValueArgument.parse(): AnnotationNode.Argument {
        val name = name?.parse(this) ?: return AnnotationNode.InvalidArgument(this)
        val rawValues = when (val value = value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (rawValues != null) {
            val elements = rawValues.map { rawValue ->
                rawValue ?: return AnnotationNode.InvalidArgument(this)
                parseValue(rawValue)
            }
            return AnnotationNode.ArrayArgument(
                name = name,
                isExplicit = origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
                elements = elements,
                node = this,
            )
        }
        val rawValue = value ?: return AnnotationNode.InvalidArgument(this)
        return AnnotationNode.ScalarArgument(
            name = name,
            isExplicit = origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
            value = parseValue(rawValue),
            node = this,
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
        is KSType -> AnnotationNode.Argument.TypeValue(type = raw.parse(this), node = this)
        is KSClassDeclaration -> {
            val classDeclaration = raw.parentDeclaration?.unwrapTypealiases() as? KSClassDeclaration ?: run {
                val expectedEntryName = raw.qualifiedName?.parse(this) as? ValidNameNode
                internalError("Failed to resolve enclosing enum class of entry: '${expectedEntryName?.name}'.")
            }
            AnnotationNode.Argument.EnumValue(
                classDeclaration = classDeclaration,
                name = raw.simpleName.parse(this),
                node = this,
            )
        }

        is KSAnnotation -> AnnotationNode.Argument.AnnotationValue(raw.parse())
        else -> internalError("Unexpected type of annotation argument value: '${raw::class.qualifiedName}'.")
    }

    private fun KSType.parse(viewNode: KSNode): TypeNode {
        if (isError) return InvalidTypeNode(viewNode)
        val parsedArguments = arguments.map { argument ->
            when (val variance = argument.variance) {
                Variance.STAR -> TypeNode.StarArgument(viewNode)
                else -> when (val parsedType = argument.type.parse(viewNode)) {
                    is InvalidTypeNode -> TypeNode.InvalidArgument(viewNode)
                    else -> when (variance) {
                        Variance.INVARIANT -> TypeNode.InvariantArgument(parsedType, viewNode)
                        Variance.COVARIANT -> TypeNode.CovariantArgument(parsedType, viewNode)
                        Variance.CONTRAVARIANT -> TypeNode.ContravariantArgument(parsedType, viewNode)
                    }
                }
            }
        }
        val canonicalType = if (declaration is KSTypeAlias) {
            val expanded = expandTypealias() ?: return InvalidTypeNode(viewNode)
            val parsedExpanded = expanded.parse(viewNode) as? ValidTypeNode ?: return InvalidTypeNode(viewNode)
            parsedExpanded.canonicalType ?: parsedExpanded
        } else {
            null
        }
        val finalType = canonicalType?.ksType ?: this
        val finalClassDeclaration = finalType.declaration as? KSClassDeclaration
        return ValidTypeNode(
            ksType = this,
            arguments = parsedArguments,
            canonicalType = canonicalType,
            classDeclaration = finalClassDeclaration,
            packageName = finalClassDeclaration?.packageName?.asString(),
            qualifiedName = finalClassDeclaration?.qualifiedName?.asString(),
            node = viewNode,
        )
    }

    private fun KSType.expandTypealias(visitedAliases: Set<KSTypeAlias> = emptySet()): KSType? {
        val alias = declaration as? KSTypeAlias ?: return this
        if (alias in visitedAliases) return null
        val expandedType = alias.type.resolve()
        val substitutions = alias.typeParameters.map { it.name.getShortName() }.zip(arguments).toMap()
        return expandedType.substituteTypeParameters(substitutions, visitedAliases + alias)
    }

    private fun KSType.substituteTypeParameters(
        substitutions: Map<String, KSTypeArgument>,
        visitedAliases: Set<KSTypeAlias>
    ): KSType? {
        if (declaration is KSTypeParameter) {
            val name = declaration.simpleName.asString()
            val argument = substitutions[name] ?: return this
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
        this?.parse() ?: InvalidTypeNode(viewNode)

    @JvmName("parseTypeParameters")
    private fun List<KSTypeParameter>.parse() = map { typeParameter ->
        TypeParameterNode(
            name = typeParameter.name.parse(typeParameter),
            variance = typeParameter.variance,
            isReified = typeParameter.isReified,
            bounds = typeParameter.bounds.map { it.parse() }.toList(),
            node = typeParameter,
        )
    }

    private fun KSName?.parse(viewNode: KSNode): NameNode {
        val name = this?.asString()?.ifBlank { null }
        if (name == null) return InvalidNameNode(viewNode)
        return ValidNameNode(name, viewNode)
    }

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"
    }
}

tailrec fun KSDeclaration.unwrapTypealiases(): KSDeclaration =
    (this as? KSTypeAlias)?.type?.resolve()?.declaration?.unwrapTypealiases() ?: this
