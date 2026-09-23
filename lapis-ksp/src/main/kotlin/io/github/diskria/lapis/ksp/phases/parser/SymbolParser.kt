package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.KMixin
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.extensions.isSubpackageOf
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import io.github.diskria.lapis.ksp.phases.parser.models.*

class SymbolParser(private val resolver: Resolver) {

    fun parsePatches(): List<ParsedPatch> =
        resolver
            .getSymbolsWithAnnotation(requireQualifiedName<KMixin>())
            .filterIsInstance<KSClassDeclaration>()
            .toList()
            .map(::parsePatch)

    private fun parsePatch(declaration: KSClassDeclaration): ParsedPatch {
        val constructorDeclarations = declaration.getConstructors()
        val constructorPropertyNames = constructorDeclarations.flatMap { decl ->
            decl.parameters.filter { it.isVal || it.isVar }.mapNotNull { it.name?.asString() }
        }
        val propertyDeclarations = declaration.getDeclaredProperties().filter {
            it.simpleName.asString() !in constructorPropertyNames
        }
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
            properties = propertyDeclarations.map(::parsePatchBodyProperty).toList(),
            functions = declaration.getDeclaredFunctions().filter { !it.isConstructor() }.map(::parsePatchFunction)
                .toList(),
            annotations = parseAnnotations(declaration),
            typeParameters = parseTypeParameters(declaration),
            node = declaration,
        )
    }

    private fun parsePatchConstructor(declaration: KSFunctionDeclaration) = ParsedPatch.Constructor(
        node = declaration,
        isPublic = declaration.isPublic(),
        parameters = declaration.parameters.map(::parsePatchConstructorParameter),
    )

    private fun parsePatchConstructorParameter(parameter: KSValueParameter) = ParsedPatch.Constructor.Parameter(
        name = parseName(parameter.name),
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
    private fun parsePatchBodyProperty(declaration: KSPropertyDeclaration) = ParsedPatch.Property(
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
        typeParameters = parseTypeParameters(declaration),
        annotations = parseAnnotations(declaration),
        node = declaration,
    )

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(declaration: KSFunctionDeclaration) = ParsedPatch.Function(
        name = declaration.simpleName.asString(),
        jvmName = resolver.getJvmName(declaration),
        parameters = declaration.parameters.map(::parsePatchFunctionParameter),
        returnType = parseTypeOrError(declaration.returnType, declaration),
        isPublic = declaration.isPublic(),
        isOpen = Modifier.OPEN in declaration.modifiers,
        isAbstract = declaration.isAbstract,
        extensionReceiverType = parseOptionalType(declaration.extensionReceiver),
        typeParameters = parseTypeParameters(declaration),
        annotations = parseAnnotations(declaration),
        node = declaration,
    )

    private fun parsePatchFunctionParameter(parameter: KSValueParameter) = ParsedPatch.Function.Parameter(
        name = parseName(parameter.name),
        type = parseType(parameter.type),
        annotations = parseAnnotations(parameter),
        node = parameter,
    )

    private fun parseAnnotations(annotated: KSAnnotated): ParsedAnnotations {
        val api = mutableListOf<ValidAnnotation>()
        val external = mutableListOf<ParsedAnnotation>()
        annotated.annotations.forEach {
            val annotation = parseAnnotation(it)
            if (annotation is ValidAnnotation && annotation.type.packageName.isSubpackageOf(API_ANNOTATIONS_PACKAGE)) {
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
        val argumentName = parseName(argument.name) as? ValidName ?: return ParsedAnnotation.InvalidArgument(argument)
        val rawValues = when (val value = argument.value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> null
        }
        if (rawValues != null) {
            var firstClass: Class<*>? = null
            val elements = rawValues.map { rawValue ->
                rawValue ?: return ParsedAnnotation.InvalidArgument(argument)
                val value = parseAnnotationArgumentValue(rawValue, argument)
                    ?: return ParsedAnnotation.InvalidArgument(argument)
                if (firstClass == null) {
                    firstClass = value.javaClass
                } else if (value.javaClass != firstClass) {
                    return ParsedAnnotation.InvalidArgument(argument)
                }
                value
            }
            return ParsedAnnotation.ArrayArgument(
                name = argumentName.name,
                isExplicit = argument.origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
                elements = elements,
                node = argument,
            )
        }
        val rawValue = argument.value ?: return ParsedAnnotation.InvalidArgument(argument)
        val value = parseAnnotationArgumentValue(rawValue, argument)
            ?: return ParsedAnnotation.InvalidArgument(argument)
        return ParsedAnnotation.ScalarArgument(
            name = argumentName.name,
            isExplicit = argument.origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC,
            value = value,
            node = argument,
        )
    }

    private fun parseAnnotationArgumentValue(rawValue: Any, argument: KSValueArgument) = when (rawValue) {
        is Boolean -> ParsedAnnotation.Argument.BooleanValue(rawValue)
        is Byte -> ParsedAnnotation.Argument.ByteValue(rawValue)
        is Short -> ParsedAnnotation.Argument.ShortValue(rawValue)
        is Int -> ParsedAnnotation.Argument.IntValue(rawValue)
        is Long -> ParsedAnnotation.Argument.LongValue(rawValue)
        is Char -> ParsedAnnotation.Argument.CharValue(rawValue)
        is Float -> ParsedAnnotation.Argument.FloatValue(rawValue)
        is Double -> ParsedAnnotation.Argument.DoubleValue(rawValue)
        is String -> ParsedAnnotation.Argument.StringValue(rawValue)
        is KSType -> (parseType(rawValue, argument) as? ValidType)?.let { ParsedAnnotation.Argument.TypeValue(it) }
        is KSClassDeclaration -> {
            val enumClassDeclaration = rawValue.parentDeclaration as? KSClassDeclaration
                ?: internalError(
                    "Expected an enum class declaration as the parent for " +
                        "enum entry '${rawValue.simpleName.asString()}', " +
                        "but found: '${rawValue.parentDeclaration?.qualifiedName?.asString() ?: "null"}' "
                )
            ParsedAnnotation.Argument.EnumValue(
                enumClassDeclaration = enumClassDeclaration,
                enumQualifiedName = enumClassDeclaration.qualifiedName?.asString(),
                entryClassDeclaration = rawValue,
                entryName = rawValue.simpleName.asString(),
            )
        }

        is KSAnnotation -> ParsedAnnotation.Argument.AnnotationValue(parseAnnotation(rawValue))
        else -> internalError("Unknown type of annotation argument with value '$rawValue'.")
    }

    private fun parseName(name: KSName?): ParsedName =
        name?.asString()?.takeIf { it.isNotBlank() }?.let { ValidName(it) } ?: InvalidName

    private fun parseType(type: KSType, node: KSNode): ParsedType =
        if (type.isError) InvalidType(node)
        else {
            val classDeclaration = type.declaration as? KSClassDeclaration ?: return InvalidType(node)
            val packageName = parseName(classDeclaration.packageName) as? ValidName ?: return InvalidType(node)
            val qualifiedName = parseName(classDeclaration.qualifiedName) as? ValidName ?: return InvalidType(node)
            ValidType(
                type = type,
                isAny = type.makeNotNullable() == resolver.builtIns.anyType,
                isUnit = type.makeNotNullable() == resolver.builtIns.unitType,
                isInterface = classDeclaration.classKind == ClassKind.INTERFACE,
                packageName = packageName.name,
                qualifiedName = qualifiedName.name,
                classDeclaration = classDeclaration,
                node = node,
            )
        }

    private fun parseType(reference: KSTypeReference): ParsedType =
        parseType(reference.resolve(), reference)

    @Suppress("unused")
    @Deprecated(
        message = "Calling 'parseType' with nullable KSTypeReference is ambiguous. " +
            "Use 'parseTypeOrError' if null indicates a resolution error, " +
            "or 'parseOptionalType' if null represents legal absence.",
        level = DeprecationLevel.ERROR
    )
    private fun parseType(reference: KSTypeReference?): Nothing {
        throw UnsupportedOperationException("Deprecated function overload cannot be called at runtime.")
    }

    private fun parseTypeOrError(reference: KSTypeReference?, node: KSNode): ParsedType =
        reference?.let { parseType(it) } ?: InvalidType(node)

    private fun parseOptionalType(reference: KSTypeReference?): ParsedType? =
        reference?.let { parseType(it) }

    private fun parseTypeParameters(declaration: KSDeclaration): List<ParsedTypeParameter> =
        declaration.typeParameters.map { typeParameter ->
            val typeParameterName = parseName(typeParameter.name) as? ValidName ?: return@map InvalidTypeParameter
            ValidTypeParameter(
                name = typeParameterName.name,
                variance = typeParameter.variance,
                isReified = typeParameter.isReified,
                bounds = typeParameter.bounds.map { parseType(it) }.toList(),
            )
        }

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"
    }
}
