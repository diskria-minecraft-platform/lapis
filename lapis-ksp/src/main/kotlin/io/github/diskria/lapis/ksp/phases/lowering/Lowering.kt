package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.KMixinModel
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Type
import io.github.diskria.lapis.ksp.phases.validator.models.TypeParameter
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class Lowering(private val options: KspOptions, private val poetesse: Poetesse) {

    fun lower(kMixins: List<KMixinModel>): List<FirKMixin> {
        val mixinSourcePackageLCP = if (!options.disableLCP) {
            kMixins.map { it.classDeclaration.packageName.asString() }.reduceOrNull { current, next ->
                val currentSegments = current.split('.')
                val nextSegments = next.split('.')
                currentSegments
                    .zip(nextSegments)
                    .takeWhile { (current, next) -> current == next }
                    .joinToString(".") { it.first }
            }.orEmpty()
        } else null
        return kMixins.map { it.lowerToFir(mixinSourcePackageLCP) }
    }

    private fun KMixinModel.lowerToFir(mixinSourcePackageLCP: String?): FirKMixin {
        val className = classDeclaration.lower()
        val generics = typeParameters.lower(enclosingGenerics = null)
        val mixin = lowerToMixin(className, generics, mixinSourcePackageLCP)
        return when (classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower(generics) }
                FirKMixinClass(
                    className = className,
                    typeVariables = generics.typeVariables,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) {
                        lowerToImpl(className, generics, constructorParameters, mixin.duck)
                    } else null,
                    constructorParameters = constructorParameters,
                    initStrategy = initStrategy,
                )
            }

            KMixinModel.Interface -> FirKMixinInterface(
                className = className,
                typeVariables = generics.typeVariables,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel.Class.ConstructorParameter.lower(generics: Generics) = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> FirKMixinClass.ConstructorParameter.Origin(
            name = name,
            targetTypeCast = type.lowerToTargetSubtypeCast(generics),
        )
    }

    private fun KMixinModel.lowerToImpl(
        sourceClassName: XClassName,
        sourceGenerics: Generics,
        constructorParameters: List<FirKMixinClass.ConstructorParameter>,
        duck: IrMixinDuck?,
    ) = IrKMixinImpl(
        originatingFile = containingFile,
        className = sourceClassName.withSuffix("_Impl"),
        typeVariables = sourceGenerics.typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? FirKMixinClass.ConstructorParameter.Origin }?.let {
                add(IrKMixinImpl.ConstructorParameter.Instance(it.name, it.targetTypeCast))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(IrKMixinImpl.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel.lowerToMixin(
        sourceClassName: XClassName,
        sourceGenerics: Generics,
        sourcePackageLCP: String?,
    ) = IrMixin(
        originatingFile = containingFile,
        className = resolveMixinClassName(sourceClassName, sourcePackageLCP),
        typeVariables = sourceGenerics.typeVariables,
        side = side,
        injections = buildList {
            addAll(injections.map { it.lowerAsMember(sourceGenerics) })
            companionObject?.let { companion -> addAll(companion.injections.map { it.lowerAsStatic(companion) }) }
        },
        duck = lowerToMixinDuck(sourceClassName, sourceGenerics),
        annotations = if (mixinAnnotations.isNotEmpty()) {
            mixinAnnotations.lower()
        } else {
            val targetClassValue = IrMixinAnnotation.Argument.ClassValue(targetClassDeclaration.lower())
            val valueArgument = IrMixinAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrMixinAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel.lowerToMixinDuck(sourceClassName: XClassName, sourceGenerics: Generics): IrMixinDuck? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface, sourceGenerics) }
        val extensions = extensionSources.map { it.lower(sourceGenerics) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                originatingFile = containingFile,
                className = sourceClassName.withSuffix("_Duck"),
                typeVariables = sourceGenerics.typeVariables,
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Extension.lower(enclosingGenerics: Generics): IrMixinDuck.Extension {
        val generics = typeParameters.lower(enclosingGenerics)
        return when (this) {
            is KMixinModel.Extension.Property -> IrMixinDuck.Extension.Property(
                type = type.lower(generics),
                sourceName = name,
                sourceGetterJvmName = getterJvmName,
                sourceSetterJvmName = setterJvmName,
                getterName = getterJvmName.withUniqueModPrefix(),
                setterName = setterJvmName?.withUniqueModPrefix(),
                receiverTargetTypeCast = receiverType.lowerToTargetSubtypeCast(generics),
                typeVariables = generics.typeVariables,
            )

            is KMixinModel.Extension.Function -> IrMixinDuck.Extension.Function(
                sourceName = name,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map { IrFunctionParameter(name = it.name, type = it.type.lower(generics)) },
                returnType = returnType?.takeIf { !it.isUnit }?.lower(generics),
                receiverTargetTypeCast = receiverType.lowerToTargetSubtypeCast(generics),
                typeVariables = generics.typeVariables,
            )
        }
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean, enclosingGenerics: Generics): IrMixinDuck.Shadow {
        val generics = typeParameters.lower(enclosingGenerics)
        return when (this) {
            is KMixinModel.Shadow.Property -> IrMixinDuck.Shadow.Property(
                type = type.lower(generics),
                sourceName = name,
                sourceGetterJvmName = getterJvmName,
                sourceSetterJvmName = setterJvmName,
                getterName = getterJvmName.withUniqueModPrefix(),
                setterName = setterJvmName?.withUniqueModPrefix(),
                mappingName = mappingName,
                modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = true),
                mixinAnnotations = if (mixinAnnotations.isNotEmpty()) {
                    mixinAnnotations.lower()
                } else {
                    listOfNotNull(
                        if (setterJvmName != null) options.mutableAnnotation else null,
                        if (FINAL in modifiers) options.finalAnnotation else null,
                        options.shadowAnnotation,
                    ).map { IrMixinAnnotation(poetesse.xClass(it), emptyList()) }
                },
                typeVariables = generics.typeVariables,
            )

            is KMixinModel.Shadow.Function -> IrMixinDuck.Shadow.Function(
                sourceName = name,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map { IrFunctionParameter(name = it.name, type = it.type.lower(generics)) },
                returnType = returnType?.takeIf { !it.isUnit }?.lower(generics),
                mappingName = mappingName,
                modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = false),
                mixinAnnotations = if (mixinAnnotations.isNotEmpty()) {
                    mixinAnnotations.lower()
                } else {
                    listOf(IrMixinAnnotation(poetesse.xClass(options.shadowAnnotation), emptyList()))
                },
                typeVariables = generics.typeVariables,
            )
        }
    }

    private fun KMixinModel.Injection.lowerAsMember(enclosingGenerics: Generics): IrMixin.MemberInjection {
        val generics = typeParameters.lower(enclosingGenerics)
        return IrMixin.MemberInjection(
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            mixinAnnotations = mixinAnnotations.lower(),
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(generics),
                    parameter.mixinAnnotations.lower(),
                )
            },
            returnType = returnType?.takeIf { !it.isUnit }?.lower(generics),
            extensionReceiverTargetTypeCast = extensionReceiverType?.lowerToTargetSubtypeCast(generics),
            typeVariables = generics.typeVariables,
        )
    }

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject): IrMixin.StaticInjection {
        val generics = typeParameters.lower(enclosingGenerics = null)
        return IrMixin.StaticInjection(
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            mixinAnnotations = mixinAnnotations.lower(),
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(generics),
                    parameter.mixinAnnotations.lower(),
                )
            },
            returnType = returnType?.takeIf { !it.isUnit }?.lower(generics),
            kMixinCompanionObjectName = companion.name,
            typeVariables = generics.typeVariables,
        )
    }

    @JvmName("lowerMixinAnnotations")
    private fun List<MixinAnnotation>.lower() = map { it.lower() }

    private fun MixinAnnotation.lower() = IrMixinAnnotation(
        className = typeClassDeclaration.lower(),
        arguments = arguments.map { argument ->
            when (argument) {
                is MixinAnnotation.ScalarArgument -> IrMixinAnnotation.ScalarArgument(
                    name = argument.name,
                    value = argument.value.lower(),
                )

                is MixinAnnotation.ArrayArgument -> IrMixinAnnotation.ArrayArgument(
                    name = argument.name,
                    elements = argument.elements.map { it.lower() },
                )
            }
        },
    )

    private fun MixinAnnotation.Argument.Value.lower(): IrMixinAnnotation.Argument.Value = when (this) {
        is MixinAnnotation.Argument.BooleanValue -> IrMixinAnnotation.Argument.BooleanValue(boolean)
        is MixinAnnotation.Argument.ByteValue -> IrMixinAnnotation.Argument.ByteValue(byte)
        is MixinAnnotation.Argument.ShortValue -> IrMixinAnnotation.Argument.ShortValue(short)
        is MixinAnnotation.Argument.IntValue -> IrMixinAnnotation.Argument.IntValue(int)
        is MixinAnnotation.Argument.LongValue -> IrMixinAnnotation.Argument.LongValue(long)
        is MixinAnnotation.Argument.CharValue -> IrMixinAnnotation.Argument.CharValue(char)
        is MixinAnnotation.Argument.FloatValue -> IrMixinAnnotation.Argument.FloatValue(float)
        is MixinAnnotation.Argument.DoubleValue -> IrMixinAnnotation.Argument.DoubleValue(double)
        is MixinAnnotation.Argument.StringValue -> IrMixinAnnotation.Argument.StringValue(string)
        is MixinAnnotation.Argument.ClassValue -> IrMixinAnnotation.Argument.ClassValue(classDeclaration.lower())
        is MixinAnnotation.Argument.EnumValue -> IrMixinAnnotation.Argument.EnumValue(classDeclaration.lower(), name)
        is MixinAnnotation.Argument.AnnotationValue -> IrMixinAnnotation.Argument.AnnotationValue(annotation.lower())
    }

    private fun Type.lowerToTargetSubtypeCast(generics: Generics) = IrTargetSubtypeCast(
        type = lower(generics),
        isUnsafeCastRequired = !isInterface,
        isTargetCastRequired = !isAny,
    )

    private fun EnumSet<Modifier>.lowerToShadowModifiers(isInterface: Boolean, isField: Boolean): List<JPModifier> {
        val result = EnumSet.copyOf(this)
        if (isField) {
            result.remove(FINAL)
        } else if (isInterface) {
            result.remove(DEFAULT)
            if (PRIVATE !in result && STATIC !in result) {
                result.add(ABSTRACT)
            }
        } else if (STATIC !in result) {
            result.add(ABSTRACT)
            if (PRIVATE in result) {
                result.remove(PRIVATE)
                result.add(PROTECTED)
            }
            result.removeAll(JavaModifiers.ABSTRACT_ILLEGALS)
        }
        return result.toList()
    }

    private fun String.withUniqueModPrefix(): String =
        options.uniqueModPrefix + this

    private fun Type.lower(generics: Generics): IrType {
        val kotlin = poetesse.xType(ksType.toTypeName(generics.resolver))
        return if (canonicalType != null) {
            IrType(kotlin, poetesse.xType(canonicalType.ksType.toTypeName(generics.resolver)))
        } else {
            IrType(kotlin, kotlin)
        }
    }

    private fun List<TypeParameter>.lower(enclosingGenerics: Generics?): Generics {
        val resolver = map { it.ksTypeParameter }.toTypeParameterResolver(enclosingGenerics?.resolver)
        val typeVariables = resolver.parametersMap.values.map { poetesse.xTypeVariable(it) }
        return Generics(resolver, typeVariables)
    }

    private fun KSClassDeclaration.lower(): XClassName =
        poetesse.xClass(toClassName())

    private fun resolveMixinClassName(sourceClassName: XClassName, sourcePackageLCP: String?): XClassName {
        val sourcePackageName = sourceClassName.packageName
        val mixinPackageName = buildString {
            append(options.mixinPackage)
            options.mixinGeneratedSubpackage?.let { append(".$it") }
            if (sourcePackageName != null && sourcePackageLCP != null && sourcePackageName != sourcePackageLCP) {
                append(".${sourcePackageName.removePrefix("$sourcePackageLCP.")}")
            }
        }
        return poetesse.xClass(mixinPackageName, sourceClassName.simpleName).withSuffix("_Generated")
    }
}

private class Generics(
    val resolver: TypeParameterResolver,
    val typeVariables: List<XTypeVariableName>,
)
