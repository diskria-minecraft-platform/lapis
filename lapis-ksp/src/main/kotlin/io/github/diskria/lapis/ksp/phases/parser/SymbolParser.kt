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

    fun parsePatches(): List<ParsedPatch> =
        resolver.getSymbolsWithAnnotation(qualifiedNameOf<KMixin>()).filterIsInstance<KSClassDeclaration>()
            .toList()
            .map(::parsePatch)

    private fun parsePatch(declaration: KSClassDeclaration): ParsedPatch {
        val constructorDeclarations = declaration.getConstructors()
        val constructorPropertyNames = constructorDeclarations.flatMap { declaration ->
            declaration.parameters.filter { it.isVal || it.isVar }.mapNotNull { it.name?.asString() }
        }
        val propertyDeclarations = declaration.getDeclaredProperties().filter {
            it.simpleName.asString() !in constructorPropertyNames
        }
        val typeParameters = declaration.typeParameters.parse()
        return ParsedPatch(
            name = declaration.simpleName.asString(),
            isClass = declaration.classKind == ClassKind.CLASS,
            isInterface = declaration.classKind == ClassKind.INTERFACE,
            isOpen = Modifier.OPEN in declaration.modifiers,
            isAbstract = Modifier.ABSTRACT in declaration.modifiers,
            isSealed = Modifier.SEALED in declaration.modifiers,
            isTopLevel = declaration.parentDeclaration == null,
            hasPackageName = declaration.packageName.asString().isNotEmpty(),
            isPublic = declaration.isPublic(),
            classDeclaration = declaration,
            companionObject = declaration.declarations.filterIsInstance<KSClassDeclaration>()
                .find { it.isCompanionObject }?.let { parsePatchCompanionObject(it) },
            constructors = constructorDeclarations.map(::parsePatchConstructor).toList(),
            properties = propertyDeclarations.map { parsePatchProperty(it) }.toList(),
            functions = declaration.getDeclaredFunctions().filter { !it.isConstructor() }.map { parsePatchFunction(it) }
                .toList(),
            annotations = parseAnnotations(declaration),
            typeParameters = typeParameters,
            node = declaration,
        )
    }

    private fun parsePatchConstructor(declaration: KSFunctionDeclaration) = ParsedPatch.Constructor(
        node = declaration,
        isPublic = declaration.isPublic(),
        parameters = declaration.parameters.map(::parsePatchConstructorParameter),
    )

    private fun parsePatchConstructorParameter(parameter: KSValueParameter) = ParsedPatch.Constructor.Parameter(
        name = parameter.name?.asString().orEmpty(),
        type = parseType(parameter.type),
        annotations = parseAnnotations(parameter),
        node = parameter,
    )

    private fun parsePatchCompanionObject(declaration: KSClassDeclaration) = ParsedPatch.CompanionObject(
        name = declaration.simpleName.asString(),
        isPublic = declaration.isPublic(),
        functions = declaration.getDeclaredFunctions().filter { !it.isConstructor() }.map(::parsePatchFunction)
            .toList(),
        node = declaration,
    )

    @OptIn(KspExperimental::class)
    private fun parsePatchProperty(declaration: KSPropertyDeclaration) = ParsedPatch.Property(
        name = declaration.simpleName.asString(),
        type = parseType(declaration.type),
        isPublic = declaration.isPublic(),
        isOpen = Modifier.OPEN in declaration.modifiers,
        isAbstract = Modifier.ABSTRACT in declaration.modifiers,
        hasExtensionReceiver = declaration.extensionReceiver != null,
        getter = declaration.getter?.let {
            ParsedPatch.Property.Getter(
                jvmName = resolver.getJvmName(it),
                annotations = parseAnnotations(it),
            )
        },
        setter = declaration.setter?.takeIf { Modifier.PUBLIC in it.modifiers }?.let {
            ParsedPatch.Property.Setter(
                jvmName = resolver.getJvmName(it),
            )
        },
        typeParameters = declaration.typeParameters.parse(),
        annotations = parseAnnotations(declaration),
        node = declaration,
    )

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(declaration: KSFunctionDeclaration) = ParsedPatch.Function(
        name = declaration.simpleName.asString(),
        jvmName = resolver.getJvmName(declaration),
        parameters = declaration.parameters.map(::parsePatchFunctionParameter),
        returnType = parseType(declaration.returnType, declaration),
        isPublic = declaration.isPublic(),
        isOpen = Modifier.OPEN in declaration.modifiers,
        isAbstract = declaration.isAbstract,
        extensionReceiverType = declaration.extensionReceiver?.let { parseType(it) },
        typeParameters = declaration.typeParameters.parse(),
        annotations = parseAnnotations(declaration),
        node = declaration,
    )

    private fun parsePatchFunctionParameter(parameter: KSValueParameter) = ParsedPatch.Function.Parameter(
        name = parameter.name?.asString().orEmpty(),
        type = parseType(parameter.type),
        annotations = parseAnnotations(parameter),
        node = parameter,
    )

    private fun parseAnnotations(annotated: KSAnnotated): ParsedAnnotations {
        val api = mutableListOf<ValidAnnotation>()
        val external = mutableListOf<ParsedAnnotation>()
        annotated.annotations.forEach {
            val annotation = parseAnnotation(it)
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

    private fun parseAnnotation(annotation: KSAnnotation): ParsedAnnotation {
        val type = parseType(annotation.annotationType) as? ValidType ?: return InvalidAnnotation(annotation)
        return ValidAnnotation(
            type = type,
            arguments = annotation.arguments.map { parseAnnotationArgument(it) },
            node = annotation,
        )
    }

    private fun parseAnnotationArgument(argument: KSValueArgument): ParsedAnnotation.Argument {
        val rawValues = when (val value = argument.value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (rawValues != null) {
            val elements = rawValues.map { rawValue ->
                rawValue ?: return ParsedAnnotation.InvalidArgument(argument)
                parseAnnotationArgumentValue(rawValue, argument)
            }
            if (elements.distinctBy { it::class }.size > 1) {
                return ParsedAnnotation.InvalidArgument(argument)
            }
            return ParsedAnnotation.ArrayArgument(
                name = argument.name?.asString().orEmpty(),
                isExplicit = argument.origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
                elements = elements,
                node = argument,
            )
        }
        val rawValue = argument.value ?: return ParsedAnnotation.InvalidArgument(argument)
        val parsedValue = parseAnnotationArgumentValue(rawValue, argument)
        return ParsedAnnotation.ScalarArgument(
            name = argument.name?.asString().orEmpty(),
            isExplicit = argument.origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
            value = parsedValue,
            node = argument,
        )
    }

    private fun parseAnnotationArgumentValue(
        rawValue: Any, argument: KSValueArgument
    ): ParsedAnnotation.Argument.Value = when (rawValue) {
        is Boolean -> ParsedAnnotation.Argument.BooleanValue(rawValue)
        is Byte -> ParsedAnnotation.Argument.ByteValue(rawValue)
        is Short -> ParsedAnnotation.Argument.ShortValue(rawValue)
        is Int -> ParsedAnnotation.Argument.IntValue(rawValue)
        is Long -> ParsedAnnotation.Argument.LongValue(rawValue)
        is Char -> ParsedAnnotation.Argument.CharValue(rawValue)
        is Float -> ParsedAnnotation.Argument.FloatValue(rawValue)
        is Double -> ParsedAnnotation.Argument.DoubleValue(rawValue)
        is String -> ParsedAnnotation.Argument.StringValue(rawValue)
        is KSType -> ParsedAnnotation.Argument.TypeValue(parseType(rawValue, argument))
        is KSClassDeclaration -> {
            val classDeclaration = rawValue.parentDeclaration?.unwrapTypealiases() as? KSClassDeclaration
                ?: internalError(
                    "Failed to resolve enclosing enum class of entry: '${rawValue.qualifiedName?.asString()}'."
                )
            ParsedAnnotation.Argument.EnumValue(classDeclaration, rawValue.simpleName.asString())
        }

        is KSAnnotation -> ParsedAnnotation.Argument.AnnotationValue(parseAnnotation(rawValue))
        else -> internalError("Unexpected type of annotation argument value: '${rawValue::class.qualifiedName}'.")
    }

    private fun parseType(type: KSType, node: KSNode): ParsedType =
        if (type.isError) InvalidType(node)
        else {
            val actualType = type.unwrapTypealiases()
            val classDeclaration = actualType.declaration as? KSClassDeclaration
            ValidType(
                type = actualType,
                isAny = actualType.makeNotNullable() == resolver.builtIns.anyType,
                isUnit = actualType.makeNotNullable() == resolver.builtIns.unitType,
                classDeclaration = classDeclaration,
                packageName = classDeclaration?.packageName?.asString().orEmpty(),
                qualifiedName = classDeclaration?.qualifiedName?.asString().orEmpty(),
                isInterface = classDeclaration?.classKind == ClassKind.INTERFACE,
                node = node,
            )
        }

    private fun parseType(reference: KSTypeReference): ParsedType =
        parseType(reference.resolve(), reference)

    private fun parseType(reference: KSTypeReference?, node: KSNode): ParsedType =
        reference?.let { parseType(it) } ?: InvalidType(node)

    private fun List<KSTypeParameter>.parse() = map { typeParameter ->
        ParsedTypeParameter(
            name = typeParameter.name.asString(),
            variance = typeParameter.variance,
            isReified = typeParameter.isReified,
            bounds = typeParameter.bounds.map { parseType(it) }.toList(),
            node = typeParameter,
        )
    }

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"
    }
}

tailrec fun KSType.unwrapTypealiases(): KSType =
    (declaration as? KSTypeAlias)?.type?.resolve()?.unwrapTypealiases() ?: this

tailrec fun KSDeclaration.unwrapTypealiases(): KSDeclaration =
    (this as? KSTypeAlias)?.type?.resolve()?.declaration?.unwrapTypealiases() ?: this
