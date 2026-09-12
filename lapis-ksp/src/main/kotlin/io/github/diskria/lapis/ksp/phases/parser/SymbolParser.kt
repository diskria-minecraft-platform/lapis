package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.annotations.Origin
import io.github.diskria.lapis.ksp.common.KSBaseTypes
import io.github.diskria.lapis.ksp.common.isUnit
import io.github.diskria.lapis.ksp.extensions.common.castOrNull
import io.github.diskria.lapis.ksp.extensions.common.lapisError
import io.github.diskria.lapis.ksp.extensions.ks.*
import io.github.diskria.lapis.ksp.extensions.ksp.KSPOrigin
import io.github.diskria.lapis.ksp.extensions.ksp.getSymbolsAnnotatedWith
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.parser.models.ParserPrepareResult
import io.github.diskria.lapis.ksp.phases.parser.models.ParserResult
import io.github.diskria.lapis.ksp.phases.parser.models.common.*
import io.github.diskria.lapis.ksp.phases.parser.models.patches.*
import java.lang.annotation.RetentionPolicy
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class SymbolParser(
    private val resolver: Resolver,
    private val baseTypes: KSBaseTypes,
    @Suppress("unused") private val logger: Logger,
) {
    fun prepare(): ParserPrepareResult =
        ParserPrepareResult(
            resolver.getSymbolsAnnotatedWith<KMixin>().filterIsInstance<KSClassDeclaration>().toList(),
        )

    fun parse(): ParserResult =
        prepare().run {
            ParserResult(
                patches = patchClassDeclarations.map(::parsePatch),
            )
        }

    private fun parsePatch(classDeclaration: KSClassDeclaration): ParsedPatch = with(classDeclaration) {
        val kMixinAnnotation = findAnnotation<KMixin>()
        ParsedPatch(
            name = name,
            side = kMixinAnnotation?.getArgumentValue(KMixin::side) ?: Side.Common,
            isClass = isClass,
            isObject = isObject,
            isOpen = isExplicitlyOpen,
            isAbstract = isExplicitlyAbstract,
            isSealed = isSealed,
            isTopLevel = parentDeclaration == null,
            hasPackageName = packageName.asString().isNotEmpty(),
            isPublic = isPublic(),
            initStrategy = kMixinAnnotation?.getArgumentValue(KMixin::initStrategy),
            classDeclaration = classDeclaration,
            targetClassDeclaration = kMixinAnnotation?.getArgumentValue(KMixin::target)?.toClassDeclaration(),
            companionObjects = companionObjectClassDeclarations.map(::parsePatchCompanionObject).toList(),
            constructors = constructorDeclarations.map(::parsePatchConstructor).toList(),
            bodyProperties = bodyPropertyDeclarations.map(::parsePatchBodyProperty).toList(),
            functions = functionDeclarations.map(::parsePatchFunction).toList(),
            annotations = annotations.map(::parseAnnotation).toList(),
        )
    }

    private fun parsePatchConstructor(constructorDeclaration: KSFunctionDeclaration): ParsedPatchConstructor =
        ParsedPatchConstructor(
            symbol = constructorDeclaration,
            isPublic = constructorDeclaration.isPublic(),
            parameters = constructorDeclaration.parameters.map(::parsePatchConstructorParameter),
        )

    private fun parsePatchConstructorParameter(parameter: KSValueParameter): ParsedPatchConstructorParameter =
        ParsedPatchConstructorParameter(
            symbol = parameter,
            type = parameter.type.resolve(),
            hasOriginAnnotation = parameter.hasAnnotation<Origin>()
        )

    private fun parsePatchCompanionObject(classDeclaration: KSClassDeclaration): ParsedPatchCompanionObject =
        ParsedPatchCompanionObject(
            symbol = classDeclaration,
            isPublic = classDeclaration.isPublic(),
            functions = classDeclaration.functionDeclarations.map(::parsePatchFunction).toList(),
        )

    @OptIn(KspExperimental::class)
    private fun parsePatchBodyProperty(
        propertyDeclaration: KSPropertyDeclaration
    ): ParsedPatchProperty = with(propertyDeclaration) {
        val shadowAnnotation = findAnnotation<KShadow>()
        val mappingNameAnnotation = findAnnotation<MappingName>()
        val getter = getter?.let {
            ParsedPatchPropertyGetter(
                jvmName = resolver.getJvmName(it),
                annotations = it.annotations.map(::parseAnnotation).toList(),
            )
        }
        val setter = takeIf { it.isMutable }?.setter?.takeIf { it.isPublic }?.let {
            ParsedPatchPropertySetter(
                jvmName = resolver.getJvmName(it),
            )
        }
        ParsedPatchProperty(
            symbol = propertyDeclaration,
            name = name,
            type = type.resolve(),
            isPublic = isPublic(),
            isOpen = isExplicitlyOpen,
            isAbstract = isExplicitlyAbstract,
            hasExtensionReceiver = hasExtensionReceiver,
            hasExtensionAnnotation = hasAnnotation<Extension>(),
            hasShadowAnnotation = shadowAnnotation != null,
            explicitMappingName = mappingNameAnnotation?.getArgumentValue(MappingName::name, explicit = true),
            shadowModifiers = shadowAnnotation?.getArgumentValue(KShadow::modifiers).orEmpty(),
            getter = getter,
            setter = setter,
        )
    }

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(
        functionDeclaration: KSFunctionDeclaration
    ): ParsedPatchFunction = with(functionDeclaration) {
        val shadowAnnotation = findAnnotation<KShadow>()
        val mappingNameAnnotation = findAnnotation<MappingName>()
        ParsedPatchFunction(
            symbol = functionDeclaration,
            name = name,
            jvmName = resolver.getJvmName(functionDeclaration),
            parameters = parameters.map(::parsePatchFunctionParameter),
            returnType = getReturnTypeOrNull(),
            hasTypeParameters = typeParameters.isNotEmpty(),
            isPublic = isPublic(),
            isOpen = isExplicitlyOpen,
            isAbstract = isAbstract,
            extensionReceiverClassDeclaration = extensionReceiver?.resolve()?.toClassDeclaration(),
            hasExtensionAnnotation = hasAnnotation<Extension>(),
            hasShadowAnnotation = shadowAnnotation != null,
            explicitMappingName = mappingNameAnnotation?.getArgumentValue(MappingName::name, explicit = true),
            shadowModifiers = shadowAnnotation?.getArgumentValue(KShadow::modifiers).orEmpty(),
            annotations = annotations.map(::parseAnnotation).toList(),
        )
    }

    private fun parsePatchFunctionParameter(
        parameter: KSValueParameter
    ): ParsedPatchFunctionParameter = with(parameter) {
        val type = type.resolve()
        return ParsedPatchFunctionParameter(
            symbol = parameter,
            name = name?.asString(),
            type = type,
            typeArguments = type.typeArguments,
            hasDefaultArgument = hasDefault,
            annotations = annotations.map(::parseAnnotation).toList(),
        )
    }

    private typealias KRetention = Retention
    private typealias JRetention = java.lang.annotation.Retention

    private fun parseAnnotation(annotation: KSAnnotation): ParsedAnnotation = with(annotation) {
        val typeClassDeclaration = annotationType.resolve().toClassDeclaration()
        val isSourceRetention = typeClassDeclaration.let {
            when (origin) {
                KSPOrigin.KOTLIN, KSPOrigin.KOTLIN_LIB -> {
                    it?.findAnnotation<KRetention>()?.getArgumentValue(KRetention::value) == AnnotationRetention.SOURCE
                }

                KSPOrigin.JAVA, KSPOrigin.JAVA_LIB -> {
                    it?.findAnnotation<JRetention>()?.getArgumentValue(JRetention::value) == RetentionPolicy.SOURCE
                }

                KSPOrigin.SYNTHETIC -> false
            }
        }
        return ParsedAnnotation(
            typeClassDeclaration = typeClassDeclaration,
            isSourceRetention = isSourceRetention,
            arguments = arguments.mapNotNull(::parseAnnotationArgument),
        )
    }

    private fun parseAnnotationArgument(argument: KSValueArgument): ParsedAnnotationArgument? = with(argument) {
        val name = name?.asString() ?: return null

        fun parseValue(value: Any): ParsedAnnotationArgumentValue = when (value) {
            is Boolean -> ParsedAnnotationBooleanArgumentValue(value)
            is Byte -> ParsedAnnotationByteArgumentValue(value)
            is Short -> ParsedAnnotationShortArgumentValue(value)
            is Int -> ParsedAnnotationIntArgumentValue(value)
            is Long -> ParsedAnnotationLongArgumentValue(value)
            is Char -> ParsedAnnotationCharArgumentValue(value)
            is Float -> ParsedAnnotationFloatArgumentValue(value)
            is Double -> ParsedAnnotationDoubleArgumentValue(value)
            is String -> ParsedAnnotationStringArgumentValue(value)
            is KSType -> ParsedAnnotationClassTypeArgumentValue(value)
            is KSClassDeclaration -> ParsedAnnotationEnumArgumentValue(value)
            is KSAnnotation -> ParsedAnnotationEmbeddedAnnotationArgumentValue(parseAnnotation(value))
            else -> lapisError("Unknown annotation argument value type: $value")
        }
        return value?.castOrNull<Iterable<Any>>()?.let { array ->
            ParsedAnnotationArrayArgument(name, isExplicit, array.map { parseValue(it) })
        } ?: value?.let { ParsedAnnotationSingleArgument(name, isExplicit, parseValue(it)) }
    }

    private inline fun <reified A : Annotation> KSAnnotation.getArgumentValue(
        property: KProperty1<A, KClass<*>>,
        explicit: Boolean = false,
    ): KSType? =
        getArgumentValue(property, baseTypes, explicit)

    private fun KSFunctionDeclaration.getReturnTypeOrNull(): KSType? =
        returnType?.resolve()?.takeIf { !it.isUnit(baseTypes) }
}
