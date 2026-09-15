package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.annotations.Origin
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedPatch
import io.github.diskria.lapis.ksp.phases.parser.models.findArgument
import io.github.diskria.lapis.ksp.phases.parser.models.findLapisApiAnnotation

class SymbolParser(
    private val resolver: Resolver,
    @Suppress("unused") private val logger: Logger,
) {
    fun parse(): List<ParsedPatch> =
        resolver
            .getSymbolsWithAnnotation(requireQualifiedName<KMixin>())
            .filterIsInstance<KSClassDeclaration>()
            .toList()
            .map(::parsePatch)

    private fun parsePatch(decl: KSClassDeclaration): ParsedPatch {
        val annotations = parseAnnotations(decl)
        val kMixinAnnotation = annotations.findLapisApiAnnotation<KMixin>()
        val constructorDeclarations = decl.getConstructors()
        val constructorPropertyNames = constructorDeclarations.flatMap { decl ->
            decl.parameters.filter { it.isVal || it.isVar }.mapNotNull { it.name?.asString() }
        }
        val propertyDeclarations = decl.getDeclaredProperties().filter {
            it.simpleName.asString() !in constructorPropertyNames
        }
        return ParsedPatch(
            name = decl.simpleName.asString(),
            env = kMixinAnnotation?.findArgument(KMixin::env),
            isClass = decl.classKind == ClassKind.CLASS,
            isObject = decl.classKind == ClassKind.OBJECT,
            isOpen = Modifier.OPEN in decl.modifiers,
            isAbstract = Modifier.ABSTRACT in decl.modifiers,
            isSealed = Modifier.SEALED in decl.modifiers,
            isTopLevel = decl.parentDeclaration == null,
            hasPackageName = decl.packageName.asString().isNotEmpty(),
            isPublic = decl.isPublic(),
            initStrategy = kMixinAnnotation?.findArgument(KMixin::initStrategy),
            classDeclaration = decl,
            targetType = kMixinAnnotation?.findArgument(KMixin::target),
            companionObject = decl.declarations.filterIsInstance<KSClassDeclaration>().find { it.isCompanionObject }
                ?.let { parsePatchCompanionObject(it) },
            constructors = constructorDeclarations.map(::parsePatchConstructor).toList(),
            properties = propertyDeclarations.map(::parsePatchBodyProperty).toList(),
            functions = decl.getDeclaredFunctions().filter { !it.isConstructor() }.map(::parsePatchFunction).toList(),
            annotations = annotations,
        )
    }

    private fun parsePatchConstructor(decl: KSFunctionDeclaration): ParsedPatch.Constructor =
        ParsedPatch.Constructor(
            symbol = decl,
            isPublic = decl.isPublic(),
            parameters = decl.parameters.map(::parsePatchConstructorParameter),
        )

    private fun parsePatchConstructorParameter(parameter: KSValueParameter): ParsedPatch.Constructor.Parameter {
        val annotations = parseAnnotations(parameter)
        return ParsedPatch.Constructor.Parameter(
            type = parameter.type.resolve(),
            hasOriginAnnotation = annotations.findLapisApiAnnotation<Origin>() != null,
            symbol = parameter,
        )
    }

    private fun parsePatchCompanionObject(decl: KSClassDeclaration): ParsedPatch.CompanionObject =
        ParsedPatch.CompanionObject(
            name = decl.simpleName.asString(),
            isPublic = decl.isPublic(),
            functions = decl.getDeclaredFunctions().filter { !it.isConstructor() }.map(::parsePatchFunction).toList(),
            symbol = decl,
        )

    @OptIn(KspExperimental::class)
    private fun parsePatchBodyProperty(decl: KSPropertyDeclaration): ParsedPatch.Property {
        val annotations = parseAnnotations(decl)
        val kShadowAnnotation = annotations.findLapisApiAnnotation<KShadow>()
        return ParsedPatch.Property(
            name = decl.simpleName.asString(),
            type = decl.type.resolve(),
            isPublic = decl.isPublic(),
            isOpen = Modifier.OPEN in decl.modifiers,
            isAbstract = Modifier.ABSTRACT in decl.modifiers,
            hasExtensionReceiver = decl.extensionReceiver != null,
            hasExtensionAnnotation = annotations.findLapisApiAnnotation<Extension>() != null,
            hasShadowAnnotation = kShadowAnnotation != null,
            explicitMappingName = annotations.findLapisApiAnnotation<MappingName>()?.findArgument(MappingName::name),
            shadowModifiers = kShadowAnnotation?.findArgument(KShadow::modifiers).orEmpty(),
            getter = decl.getter?.let {
                ParsedPatch.Property.Getter(
                    jvmName = resolver.getJvmName(it),
                    annotations = parseAnnotations(it),
                )
            },
            setter = decl.setter?.takeIf { Modifier.PUBLIC in it.modifiers }?.let {
                ParsedPatch.Property.Setter(
                    jvmName = resolver.getJvmName(it),
                )
            },
            annotations = annotations,
            symbol = decl,
        )
    }

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(decl: KSFunctionDeclaration): ParsedPatch.Function {
        val annotations = parseAnnotations(decl)
        val kShadowAnnotation = annotations.findLapisApiAnnotation<KShadow>()
        return ParsedPatch.Function(
            name = decl.simpleName.asString(),
            jvmName = resolver.getJvmName(decl),
            parameters = decl.parameters.map(::parsePatchFunctionParameter),
            returnType = decl.returnType?.resolve()?.takeIf { it != resolver.builtIns.unitType },
            hasTypeParameters = decl.typeParameters.isNotEmpty(),
            isPublic = decl.isPublic(),
            isOpen = Modifier.OPEN in decl.modifiers,
            isAbstract = decl.isAbstract,
            extensionReceiverType = decl.extensionReceiver?.resolve(),
            hasExtensionAnnotation = annotations.findLapisApiAnnotation<Extension>() != null,
            hasShadowAnnotation = kShadowAnnotation != null,
            explicitMappingName = annotations.findLapisApiAnnotation<MappingName>()?.findArgument(MappingName::name),
            shadowModifiers = kShadowAnnotation?.findArgument(KShadow::modifiers).orEmpty(),
            annotations = annotations,
            symbol = decl,
        )
    }

    private fun parsePatchFunctionParameter(parameter: KSValueParameter): ParsedPatch.Function.Parameter =
        ParsedPatch.Function.Parameter(
            name = parameter.name?.asString(),
            type = parameter.type.resolve(),
            annotations = parseAnnotations(parameter),
            symbol = parameter,
        )

    private fun parseAnnotations(annotated: KSAnnotated): List<ParsedAnnotation?> =
        annotated.annotations.map(::parseAnnotation).toList()

    private fun parseAnnotation(annotation: KSAnnotation): ParsedAnnotation? {
        val arguments = annotation.arguments.map { argument ->
            val name = argument.name?.asString() ?: return null
            val arrayElements = when (val value = argument.value) {
                is Collection<*> -> value.toList()
                is Array<*> -> value.toList()
                else -> null
            }
            val isExplicit = argument.origin != com.google.devtools.ksp.symbol.Origin.SYNTHETIC
            if (arrayElements != null) {
                val normalizedElements = arrayElements.filterNotNull().takeIf { it.size == arrayElements.size }
                    ?: return null
                ParsedAnnotation.ArrayArgument(
                    name = name,
                    isExplicit = isExplicit,
                    elements = normalizedElements.map { parseAnnotationArgumentValue(it, name) ?: return null },
                )
            } else {
                val normalizedValue = argument.value ?: return null
                ParsedAnnotation.ScalarArgument(
                    name = name,
                    isExplicit = isExplicit,
                    value = parseAnnotationArgumentValue(normalizedValue, name) ?: return null,
                )
            }
        }
        val typeClassDeclaration = annotation.annotationType.resolve().declaration as? KSClassDeclaration
        return ParsedAnnotation(
            typeClassDeclaration = typeClassDeclaration,
            isLapisApi = typeClassDeclaration?.packageName?.asString() == "io.github.diskria.lapis.annotations",
            qualifiedName = typeClassDeclaration?.qualifiedName?.asString(),
            arguments = arguments,
        )
    }

    private fun parseAnnotationArgumentValue(value: Any, argumentName: String): ParsedAnnotation.Argument.Value? =
        when (value) {
            is Boolean -> ParsedAnnotation.Argument.BooleanValue(value)
            is Byte -> ParsedAnnotation.Argument.ByteValue(value)
            is Short -> ParsedAnnotation.Argument.ShortValue(value)
            is Int -> ParsedAnnotation.Argument.IntValue(value)
            is Long -> ParsedAnnotation.Argument.LongValue(value)
            is Char -> ParsedAnnotation.Argument.CharValue(value)
            is Float -> ParsedAnnotation.Argument.FloatValue(value)
            is Double -> ParsedAnnotation.Argument.DoubleValue(value)
            is String -> ParsedAnnotation.Argument.StringValue(value)
            is KSType -> ParsedAnnotation.Argument.TypeValue(value)
            is KSClassDeclaration -> {
                val enumClassDeclaration = value.parentDeclaration as? KSClassDeclaration
                    ?: internalError(
                        "Expected an enum class declaration as the parent for " +
                            "enum entry '${value.simpleName.asString()}', " +
                            "but found: '${value.parentDeclaration?.qualifiedName?.asString() ?: "null"}' " +
                            "(annotation argument: '$argumentName')."
                    )
                ParsedAnnotation.Argument.EnumValue(
                    enumClassDeclaration,
                    enumClassDeclaration.qualifiedName?.asString(),
                    value.simpleName.asString(),
                )
            }

            is KSAnnotation -> parseAnnotation(value)?.let { ParsedAnnotation.Argument.AnnotationValue(it) }
            else -> internalError("Unknown type of annotation argument '$argumentName' with value '$value'.")
        }
}
