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
import io.github.diskria.lapis.ksp.extensions.ksp.KSPOrigin
import io.github.diskria.lapis.ksp.extensions.ksp.getSymbolsAnnotatedWith
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedPatch
import java.lang.annotation.RetentionPolicy
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class SymbolParser(
    private val resolver: Resolver,
    @Suppress("unused") private val logger: Logger,
) {
    fun parse(): List<ParsedPatch> =
        resolver.getSymbolsAnnotatedWith<KMixin>().filterIsInstance<KSClassDeclaration>().toList().map(::parsePatch)

    private fun parsePatch(decl: KSClassDeclaration): ParsedPatch {
        val kMixinAnnotation = decl.findAnnotation<KMixin>()
        return ParsedPatch(
            name = decl.name,
            env = kMixinAnnotation?.getArgumentValue(KMixin::env),
            isClass = decl.isClass,
            isObject = decl.isObject,
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isExplicitlyAbstract,
            isSealed = decl.isSealed,
            isTopLevel = decl.parentDeclaration == null,
            hasPackageName = decl.packageName.asString().isNotEmpty(),
            isPublic = decl.isPublic(),
            initStrategy = kMixinAnnotation?.getArgumentValue(KMixin::initStrategy),
            classDeclaration = decl,
            targetClassDeclaration = kMixinAnnotation?.getArgumentValue(KMixin::target)?.toClassDeclaration(),
            companionObjects = decl.companionObjectClassDeclarations.map(::parsePatchCompanionObject).toList(),
            constructors = decl.constructorDeclarations.map(::parsePatchConstructor).toList(),
            bodyProperties = decl.bodyPropertyDeclarations.map(::parsePatchBodyProperty).toList(),
            functions = decl.functionDeclarations.map(::parsePatchFunction).toList(),
            annotations = parseAnnotations(decl.annotations),
        )
    }

    private fun parsePatchConstructor(decl: KSFunctionDeclaration): ParsedPatch.Constructor =
        ParsedPatch.Constructor(
            symbol = decl,
            isPublic = decl.isPublic(),
            parameters = decl.parameters.map(::parsePatchConstructorParameter),
        )

    private fun parsePatchConstructorParameter(parameter: KSValueParameter): ParsedPatch.Constructor.Parameter =
        ParsedPatch.Constructor.Parameter(
            symbol = parameter,
            type = parameter.type.resolve(),
            hasOriginAnnotation = parameter.hasAnnotation<Origin>()
        )

    private fun parsePatchCompanionObject(decl: KSClassDeclaration): ParsedPatch.CompanionObject =
        ParsedPatch.CompanionObject(
            symbol = decl,
            isPublic = decl.isPublic(),
            functions = decl.functionDeclarations.map(::parsePatchFunction).toList(),
        )

    @OptIn(KspExperimental::class)
    private fun parsePatchBodyProperty(decl: KSPropertyDeclaration): ParsedPatch.Property {
        val shadowAnnotation = decl.findAnnotation<KShadow>()
        return ParsedPatch.Property(
            symbol = decl,
            name = decl.name,
            type = decl.type.resolve(),
            isPublic = decl.isPublic(),
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isExplicitlyAbstract,
            hasExtensionReceiver = decl.hasExtensionReceiver,
            hasExtensionAnnotation = decl.hasAnnotation<Extension>(),
            hasShadowAnnotation = shadowAnnotation != null,
            explicitMappingName = decl.findAnnotation<MappingName>()
                ?.getArgumentValue(MappingName::name, explicit = true),
            shadowModifiers = shadowAnnotation?.getArgumentValue(KShadow::modifiers).orEmpty(),
            getter = decl.getter?.let {
                ParsedPatch.Property.Getter(
                    jvmName = resolver.getJvmName(it),
                    annotations = parseAnnotations(it.annotations),
                )
            },
            setter = decl.takeIf { it.isMutable }?.setter?.takeIf { it.isPublic }?.let {
                ParsedPatch.Property.Setter(
                    jvmName = resolver.getJvmName(it),
                )
            },
        )
    }

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(decl: KSFunctionDeclaration): ParsedPatch.Function {
        val shadowAnnotation = decl.findAnnotation<KShadow>()
        return ParsedPatch.Function(
            symbol = decl,
            name = decl.name,
            jvmName = resolver.getJvmName(decl),
            parameters = decl.parameters.map(::parsePatchFunctionParameter),
            returnType = decl.getReturnTypeOrNull(),
            hasTypeParameters = decl.typeParameters.isNotEmpty(),
            isPublic = decl.isPublic(),
            isOpen = decl.isExplicitlyOpen,
            isAbstract = decl.isAbstract,
            extensionReceiverClassDeclaration = decl.extensionReceiver?.resolve()?.toClassDeclaration(),
            hasExtensionAnnotation = decl.hasAnnotation<Extension>(),
            hasShadowAnnotation = shadowAnnotation != null,
            explicitMappingName = decl.findAnnotation<MappingName>()
                ?.getArgumentValue(MappingName::name, explicit = true),
            shadowModifiers = shadowAnnotation?.getArgumentValue(KShadow::modifiers).orEmpty(),
            annotations = parseAnnotations(decl.annotations),
        )
    }

    private fun parsePatchFunctionParameter(parameter: KSValueParameter): ParsedPatch.Function.Parameter =
        ParsedPatch.Function.Parameter(
            symbol = parameter,
            name = parameter.name?.asString(),
            type = parameter.type.resolve(),
            annotations = parseAnnotations(parameter.annotations),
        )

    private typealias KRetention = Retention
    private typealias JRetention = java.lang.annotation.Retention

    private fun parseAnnotations(annotations: Sequence<KSAnnotation>): List<ParsedAnnotation> =
        annotations.map(::parseAnnotation).toList()

    private fun parseAnnotation(annotation: KSAnnotation): ParsedAnnotation {
        val typeClassDeclaration = annotation.annotationType.resolve().toClassDeclaration()
        return ParsedAnnotation(
            classDeclaration = typeClassDeclaration,
            isSourceRetention = typeClassDeclaration.let {
                when (annotation.origin) {
                    KSPOrigin.KOTLIN, KSPOrigin.KOTLIN_LIB -> {
                        it?.findAnnotation<KRetention>()
                            ?.getArgumentValue(KRetention::value) == AnnotationRetention.SOURCE
                    }

                    KSPOrigin.JAVA, KSPOrigin.JAVA_LIB -> {
                        it?.findAnnotation<JRetention>()
                            ?.getArgumentValue(JRetention::value) == RetentionPolicy.SOURCE
                    }

                    KSPOrigin.SYNTHETIC -> false
                }
            },
            arguments = annotation.arguments.mapNotNull { argument ->
                val name = argument.name?.asString() ?: return@mapNotNull null
                val (values, isArray) = when (val value = argument.value) {
                    is Iterable<*> -> value.toList() to true
                    is Array<*> -> value.toList() to true
                    else -> listOf(value) to false
                }
                val normalizedValues = values.filterNotNull().ifEmpty { return@mapNotNull null }
                ParsedAnnotation.Argument(
                    name,
                    argument.isExplicit,
                    normalizedValues.map { parseAnnotationArgumentValue(it) },
                    isArray,
                )
            }
        )
    }

    private fun parseAnnotationArgumentValue(value: Any): ParsedAnnotation.Argument.Value = when (value) {
        is Boolean -> ParsedAnnotation.Argument.BooleanValue(value)
        is Byte -> ParsedAnnotation.Argument.ByteValue(value)
        is Short -> ParsedAnnotation.Argument.ShortValue(value)
        is Int -> ParsedAnnotation.Argument.IntValue(value)
        is Long -> ParsedAnnotation.Argument.LongValue(value)
        is Char -> ParsedAnnotation.Argument.CharValue(value)
        is Float -> ParsedAnnotation.Argument.FloatValue(value)
        is Double -> ParsedAnnotation.Argument.DoubleValue(value)
        is String -> ParsedAnnotation.Argument.StringValue(value)
        is KSType -> ParsedAnnotation.Argument.ClassValue(value)
        is KSClassDeclaration -> {
            ParsedAnnotation.Argument.EnumValue(value.parentDeclaration as? KSClassDeclaration, value.name)
        }

        is KSAnnotation -> ParsedAnnotation.Argument.AnnotationValue(parseAnnotation(value))
        else -> internalError("Unknown annotation argument type for value: '$value'.")
    }

    private inline fun <reified A : Annotation> KSAnnotation.getArgumentValue(
        property: KProperty1<A, KClass<*>>,
        explicit: Boolean = false,
    ): KSType? = getArgumentValue(property, resolver.builtIns, explicit)

    private fun KSFunctionDeclaration.getReturnTypeOrNull(): KSType? =
        returnType?.resolve()?.takeIf { it != resolver.builtIns.unitType }
}
