package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.annotations.Origin
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.extensions.internalError
import io.github.diskria.lapis.ksp.extensions.ks.*
import io.github.diskria.lapis.ksp.extensions.ksp.getSymbolsAnnotatedWith
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedPatch
import io.github.diskria.lapis.ksp.phases.parser.models.findArgument
import io.github.diskria.lapis.ksp.phases.parser.models.findLapisApiAnnotation

class SymbolParser(
    private val resolver: Resolver,
    @Suppress("unused") private val logger: Logger,
) {
    fun parse(): List<ParsedPatch> =
        resolver.getSymbolsAnnotatedWith<KMixin>().filterIsInstance<KSClassDeclaration>().toList().map(::parsePatch)

    private fun parsePatch(decl: KSClassDeclaration): ParsedPatch {
        val annotations = parseAnnotations(decl)
        val kMixinAnnotation = annotations.findLapisApiAnnotation<KMixin>()
        return ParsedPatch(
            name = decl.name,
            env = kMixinAnnotation?.findArgument(KMixin::env),
            isClass = decl.isClass,
            isObject = decl.isObject,
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isExplicitlyAbstract,
            isSealed = decl.isSealed,
            isTopLevel = decl.parentDeclaration == null,
            hasPackageName = decl.packageName.asString().isNotEmpty(),
            isPublic = decl.isPublic(),
            initStrategy = kMixinAnnotation?.findArgument(KMixin::initStrategy),
            classDeclaration = decl,
            targetClassDeclaration = kMixinAnnotation?.findArgument(KMixin::target)?.declaration as? KSClassDeclaration,
            companionObjects = decl.companionObjectClassDeclarations.map(::parsePatchCompanionObject).toList(),
            constructors = decl.constructorDeclarations.map(::parsePatchConstructor).toList(),
            bodyProperties = decl.bodyPropertyDeclarations.map(::parsePatchBodyProperty).toList(),
            functions = decl.functionDeclarations.map(::parsePatchFunction).toList(),
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
            symbol = parameter,
            type = parameter.type.resolve(),
            hasOriginAnnotation = annotations.findLapisApiAnnotation<Origin>() != null,
        )
    }

    private fun parsePatchCompanionObject(decl: KSClassDeclaration): ParsedPatch.CompanionObject =
        ParsedPatch.CompanionObject(
            symbol = decl,
            isPublic = decl.isPublic(),
            functions = decl.functionDeclarations.map(::parsePatchFunction).toList(),
        )

    @OptIn(KspExperimental::class)
    private fun parsePatchBodyProperty(decl: KSPropertyDeclaration): ParsedPatch.Property {
        val annotations = parseAnnotations(decl)
        val kShadowAnnotation = annotations.findLapisApiAnnotation<KShadow>()
        return ParsedPatch.Property(
            symbol = decl,
            name = decl.name,
            type = decl.type.resolve(),
            isPublic = decl.isPublic(),
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isExplicitlyAbstract,
            hasExtensionReceiver = decl.hasExtensionReceiver,
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
            setter = decl.setter?.takeIf { it.isPublic }?.let {
                ParsedPatch.Property.Setter(
                    jvmName = resolver.getJvmName(it),
                )
            },
            annotations = annotations,
        )
    }

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(decl: KSFunctionDeclaration): ParsedPatch.Function {
        val annotations = parseAnnotations(decl)
        val kShadowAnnotation = annotations.findLapisApiAnnotation<KShadow>()
        return ParsedPatch.Function(
            symbol = decl,
            name = decl.name,
            jvmName = resolver.getJvmName(decl),
            parameters = decl.parameters.map(::parsePatchFunctionParameter),
            returnType = decl.returnType?.resolve()?.takeIf { it != resolver.builtIns.unitType },
            hasTypeParameters = decl.typeParameters.isNotEmpty(),
            isPublic = decl.isPublic(),
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isAbstract,
            extensionReceiverClassDeclaration = decl.extensionReceiver?.resolve()?.declaration as? KSClassDeclaration,
            hasExtensionAnnotation = annotations.findLapisApiAnnotation<Extension>() != null,
            hasShadowAnnotation = kShadowAnnotation != null,
            explicitMappingName = annotations.findLapisApiAnnotation<MappingName>()?.findArgument(MappingName::name),
            shadowModifiers = kShadowAnnotation?.findArgument(KShadow::modifiers).orEmpty(),
            annotations = annotations,
        )
    }

    private fun parsePatchFunctionParameter(parameter: KSValueParameter): ParsedPatch.Function.Parameter =
        ParsedPatch.Function.Parameter(
            symbol = parameter,
            name = parameter.name?.asString(),
            type = parameter.type.resolve(),
            annotations = parseAnnotations(parameter),
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
            if (arrayElements != null) {
                val normalizedElements = arrayElements.filterNotNull().takeIf { it.size == arrayElements.size }
                    ?: return null
                ParsedAnnotation.ArrayArgument(
                    name = name,
                    isExplicit = argument.isExplicit,
                    elements = normalizedElements.map { parseAnnotationArgumentValue(it, name) ?: return null },
                )
            } else {
                val normalizedValue = argument.value ?: return null
                ParsedAnnotation.ScalarArgument(
                    name = name,
                    isExplicit = argument.isExplicit,
                    value = parseAnnotationArgumentValue(normalizedValue, name) ?: return null,
                )
            }
        }
        val typeClassDeclaration = annotation.annotationType.resolve().declaration as? KSClassDeclaration
        return ParsedAnnotation(
            classDeclaration = typeClassDeclaration,
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
                val enumClassDeclaration = value.parentDeclaration as? KSClassDeclaration ?: internalError(
                    "Parent declaration for expected enum entry is not a class: '${value.qualifiedName?.asString()}'."
                )
                ParsedAnnotation.Argument.EnumValue(
                    enumClassDeclaration,
                    enumClassDeclaration.qualifiedName?.asString(),
                    value.name
                )
            }

            is KSAnnotation -> parseAnnotation(value)?.let { ParsedAnnotation.Argument.AnnotationValue(it) }
            else -> internalError("Unknown type of annotation argument '$argumentName' with value '$value'.")
        }
}
