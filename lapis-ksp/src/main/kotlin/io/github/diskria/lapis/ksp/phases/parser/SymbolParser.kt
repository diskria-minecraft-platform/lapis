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

    fun parseKMixins(): Sequence<ParsedKMixin> =
        resolver
            .getSymbolsWithAnnotation(qualifiedNameOf<KMixin>())
            .filterIsInstance<KSClassDeclaration>()
            .map { it.parseAsRoot() }

    private fun KSClassDeclaration.parseAsRoot(): ParsedKMixin {
        val allFunctionDeclarations = getDeclaredFunctions()
        val constructorDeclarations = allFunctionDeclarations.filter { it.isConstructor() }
        val primaryConstructorPropertyNames = constructorDeclarations.flatMap { constructor ->
            constructor.parameters.mapNotNull { parameter ->
                if (parameter.isVal || parameter.isVar) parameter.name?.asString() else null
            }
        }.toSet()
        val propertyDeclarations = getDeclaredProperties().filter {
            it.simpleName.asString() !in primaryConstructorPropertyNames
        }
        val functionDeclarations = allFunctionDeclarations.filter { !it.isConstructor() }
        return ParsedKMixin(
            name = simpleName.asString(),
            isClass = classKind == ClassKind.CLASS,
            isInterface = classKind == ClassKind.INTERFACE,
            isOpen = Modifier.OPEN in modifiers,
            isAbstract = Modifier.ABSTRACT in modifiers,
            isSealed = Modifier.SEALED in modifiers,
            isTopLevel = parentDeclaration == null,
            hasPackageName = packageName.asString().isNotEmpty(),
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

    private fun KSFunctionDeclaration.parseAsConstructor() = ParsedKMixin.Constructor(
        isPublic = isPublic(),
        parameters = parameters.map { it.parseAsConstructorParameter() },
        node = this,
    )

    private fun KSValueParameter.parseAsConstructorParameter() = ParsedKMixin.Constructor.Parameter(
        name = name?.asString().orEmpty(),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    @OptIn(KspExperimental::class)
    private fun KSPropertyDeclaration.parse() = ParsedKMixin.Property(
        name = simpleName.asString(),
        type = type.parse(),
        isPublic = isPublic(),
        isOpen = Modifier.OPEN in modifiers,
        isAbstract = Modifier.ABSTRACT in modifiers,
        hasExtensionReceiver = extensionReceiver != null,
        getter = getter?.let {
            ParsedKMixin.Property.Getter(
                jvmName = resolver.getJvmName(it),
                annotations = parseAnnotations(),
            )
        },
        setter = setter?.takeIf { Modifier.PUBLIC in it.modifiers }?.let {
            ParsedKMixin.Property.Setter(
                jvmName = resolver.getJvmName(it),
            )
        },
        typeParameters = typeParameters.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    @OptIn(KspExperimental::class)
    private fun KSFunctionDeclaration.parseAsFunction() = ParsedKMixin.Function(
        name = simpleName.asString(),
        jvmName = resolver.getJvmName(this),
        parameters = parameters.map { it.parseAsFunctionParameter() },
        returnType = returnType.parse(this),
        isPublic = isPublic(),
        isOpen = Modifier.OPEN in modifiers,
        isAbstract = isAbstract,
        extensionReceiverType = extensionReceiver?.parse(),
        typeParameters = typeParameters.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    private fun KSValueParameter.parseAsFunctionParameter() = ParsedKMixin.Function.Parameter(
        name = name?.asString().orEmpty(),
        type = type.parse(),
        annotations = parseAnnotations(),
        node = this,
    )

    private fun KSClassDeclaration.parseAsCompanionObject() = ParsedKMixin.CompanionObject(
        name = simpleName.asString(),
        isPublic = isPublic(),
        functions = getDeclaredFunctions().filter { !it.isConstructor() }.map { it.parseAsFunction() }.toList(),
        node = this,
    )


    private fun KSAnnotated.parseAnnotations(): ParsedAnnotations {
        val api = mutableListOf<ValidAnnotation>()
        val external = mutableListOf<ParsedAnnotation>()
        annotations.forEach {
            val annotation = it.parse()
            if (annotation is ValidAnnotation &&
                annotation.type.packageName?.isSubpackageOf(API_ANNOTATIONS_PACKAGE) == true
            ) {
                api += annotation
            } else {
                external += annotation
            }
        }
        return ParsedAnnotations(api, external)
    }

    private fun KSAnnotation.parse(): ParsedAnnotation {
        val type = annotationType.parse() as? ValidType ?: return InvalidAnnotation(this)
        return ValidAnnotation(
            type = type,
            arguments = arguments.map { it.parse() },
            node = this,
        )
    }

    private fun KSValueArgument.parse(): ParsedAnnotation.Argument {
        val rawValues = when (val value = value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        val name = name?.asString() ?: return ParsedAnnotation.InvalidArgument(this)
        if (rawValues != null) {
            val elements = rawValues.map { rawValue ->
                rawValue ?: return ParsedAnnotation.InvalidArgument(this)
                parseValue(rawValue)
            }
            return ParsedAnnotation.ArrayArgument(
                name = name,
                isExplicit = origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
                elements = elements,
                node = this,
            )
        }
        val rawValue = value ?: return ParsedAnnotation.InvalidArgument(this)
        return ParsedAnnotation.ScalarArgument(
            name = name,
            isExplicit = origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
            value = parseValue(rawValue),
            node = this,
        )
    }

    private fun KSValueArgument.parseValue(raw: Any): ParsedAnnotation.Argument.Value = when (raw) {
        is Boolean -> ParsedAnnotation.Argument.BooleanValue(raw)
        is Byte -> ParsedAnnotation.Argument.ByteValue(raw)
        is Short -> ParsedAnnotation.Argument.ShortValue(raw)
        is Int -> ParsedAnnotation.Argument.IntValue(raw)
        is Long -> ParsedAnnotation.Argument.LongValue(raw)
        is Char -> ParsedAnnotation.Argument.CharValue(raw)
        is Float -> ParsedAnnotation.Argument.FloatValue(raw)
        is Double -> ParsedAnnotation.Argument.DoubleValue(raw)
        is String -> ParsedAnnotation.Argument.StringValue(raw)
        is KSType -> ParsedAnnotation.Argument.TypeValue(raw.parse(this))
        is KSClassDeclaration -> {
            val classDeclaration = raw.parentDeclaration?.unwrapTypealiases() as? KSClassDeclaration
                ?: internalError("Failed to resolve enclosing enum class of entry: '${raw.qualifiedName?.asString()}'.")
            ParsedAnnotation.Argument.EnumValue(classDeclaration, raw.simpleName.asString())
        }

        is KSAnnotation -> ParsedAnnotation.Argument.AnnotationValue(raw.parse())
        else -> internalError("Unexpected type of annotation argument value: '${raw::class.qualifiedName}'.")
    }

    private fun KSType.parse(viewNode: KSNode): ParsedType {
        if (isError) return InvalidType(viewNode)
        val parsedArguments = arguments.map { argument ->
            if (argument.variance == Variance.STAR) {
                ParsedType.StarArgument(viewNode)
            } else {
                val parsedType = argument.type.parse(viewNode)
                if (parsedType is InvalidType) {
                    ParsedType.InvalidArgument(viewNode)
                } else {
                    ParsedType.VarianceArgument(argument.variance, parsedType, viewNode)
                }
            }
        }
        val canonicalType = if (declaration is KSTypeAlias) {
            val expanded = expandTypealias() ?: return InvalidType(viewNode)
            val parsedExpanded = expanded.parse(viewNode) as? ValidType ?: return InvalidType(viewNode)
            parsedExpanded.canonicalType ?: parsedExpanded
        } else {
            null
        }
        val finalType = canonicalType?.ksType ?: this
        val finalClassDeclaration = finalType.declaration as? KSClassDeclaration
        return ValidType(
            ksType = this,
            arguments = parsedArguments,
            canonicalType = canonicalType,
            isAny = finalType.makeNotNullable() == resolver.builtIns.anyType,
            isUnit = finalType.makeNotNullable() == resolver.builtIns.unitType,
            classDeclaration = finalClassDeclaration,
            packageName = finalClassDeclaration?.packageName?.asString(),
            qualifiedName = finalClassDeclaration?.qualifiedName?.asString(),
            isInterface = finalClassDeclaration?.classKind == ClassKind.INTERFACE,
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

    private fun KSTypeReference.parse(): ParsedType =
        resolve().parse(this)

    private fun KSTypeReference?.parse(viewNode: KSNode): ParsedType =
        this?.parse() ?: InvalidType(viewNode)

    @JvmName("parseTypeParameters")
    private fun List<KSTypeParameter>.parse() = map { typeParameter ->
        ParsedTypeParameter(
            name = typeParameter.name.asString(),
            variance = typeParameter.variance,
            isReified = typeParameter.isReified,
            bounds = typeParameter.bounds.map { it.parse() }.toList(),
            node = typeParameter,
        )
    }

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"
    }
}


tailrec fun KSDeclaration.unwrapTypealiases(): KSDeclaration =
    (this as? KSTypeAlias)?.type?.resolve()?.declaration?.unwrapTypealiases() ?: this
