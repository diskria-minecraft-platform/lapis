package io.github.diskria.lapis.ksp.phases.parser

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.annotations.*
import io.github.diskria.lapis.annotations.Origin
import io.github.diskria.lapis.ksp.extensions.castOrNull
import io.github.diskria.lapis.ksp.extensions.ks.*
import io.github.diskria.lapis.ksp.extensions.ksp.KSPOrigin
import io.github.diskria.lapis.ksp.extensions.ksp.getSymbolsAnnotatedWith
import io.github.diskria.lapis.ksp.extensions.lapisError
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.parser.models.ParserResult
import io.github.diskria.lapis.ksp.phases.parser.models.patches.*
import java.lang.annotation.RetentionPolicy
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class SymbolParser(
    private val resolver: Resolver,
    @Suppress("unused") private val logger: Logger,
) {
    fun parse(): ParserResult =
        ParserResult(
            resolver
                .getSymbolsAnnotatedWith<KMixin>()
                .filterIsInstance<KSClassDeclaration>()
                .toList()
                .map(::parsePatch),
        )

    private fun parsePatch(decl: KSClassDeclaration): ParsedPatch {
        val kMixinAnnotation = decl.findAnnotation<KMixin>()
        return ParsedPatch(
            name = decl.name,
            env = kMixinAnnotation?.getArgumentValue(KMixin::env) ?: Env.Common,
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
            annotations = decl.annotations.map(::parseAnnotation).toList(),
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
    private fun parsePatchBodyProperty(decl: KSPropertyDeclaration): ParsedPatchProperty {
        val shadowAnnotation = decl.findAnnotation<KShadow>()
        return ParsedPatchProperty(
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
                ParsedPatchPropertyGetter(
                    jvmName = resolver.getJvmName(it),
                    annotations = it.annotations.map(::parseAnnotation).toList(),
                )
            },
            setter = decl.takeIf { it.isMutable }?.setter?.takeIf { it.isPublic }?.let {
                ParsedPatchPropertySetter(
                    jvmName = resolver.getJvmName(it),
                )
            },
        )
    }

    @OptIn(KspExperimental::class)
    private fun parsePatchFunction(decl: KSFunctionDeclaration): ParsedPatchFunction {
        val shadowAnnotation = decl.findAnnotation<KShadow>()
        val mappingNameAnnotation = decl.findAnnotation<MappingName>()
        return ParsedPatchFunction(
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
            explicitMappingName = mappingNameAnnotation?.getArgumentValue(MappingName::name, explicit = true),
            shadowModifiers = shadowAnnotation?.getArgumentValue(KShadow::modifiers).orEmpty(),
            annotations = decl.annotations.map(::parseAnnotation).toList(),
        )
    }

    private fun parsePatchFunctionParameter(parameter: KSValueParameter): ParsedPatchFunctionParameter =
        ParsedPatchFunctionParameter(
            symbol = parameter,
            name = parameter.name?.asString(),
            type = parameter.type.resolve(),
            annotations = parameter.annotations.map(::parseAnnotation).toList(),
        )

    private typealias KRetention = Retention
    private typealias JRetention = java.lang.annotation.Retention

    private fun parseAnnotation(annotation: KSAnnotation): ParsedAnnotation {
        val typeClassDeclaration = annotation.annotationType.resolve().toClassDeclaration()
        return ParsedAnnotation(
            typeClassDeclaration = typeClassDeclaration,
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
            arguments = annotation.arguments.mapNotNull(::parseAnnotationArgument),
        )
    }

    private fun parseAnnotationArgument(argument: KSValueArgument): ParsedAnnotationArgument? {
        val name = argument.name?.asString() ?: return null

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
            is KSClassDeclaration -> {
                ParsedAnnotationEnumArgumentValue(value.parentDeclaration as? KSClassDeclaration, value.name)
            }

            is KSAnnotation -> ParsedAnnotationEmbeddedAnnotationArgumentValue(parseAnnotation(value))
            else -> lapisError("Unknown annotation argument type for value: $value")
        }
        return argument.value?.castOrNull<Iterable<Any>>()?.let { array ->
            ParsedAnnotationArrayArgument(name, argument.isExplicit, array.map { parseValue(it) })
        } ?: argument.value?.let { ParsedAnnotationSingleArgument(name, argument.isExplicit, parseValue(it)) }
    }

    private inline fun <reified A : Annotation> KSAnnotation.getArgumentValue(
        property: KProperty1<A, KClass<*>>,
        explicit: Boolean = false,
    ): KSType? =
        getArgumentValue(property, resolver.builtIns, explicit)

    private fun KSFunctionDeclaration.getReturnTypeOrNull(): KSType? =
        returnType?.resolve()?.takeIf { it != resolver.builtIns.unitType }
}
