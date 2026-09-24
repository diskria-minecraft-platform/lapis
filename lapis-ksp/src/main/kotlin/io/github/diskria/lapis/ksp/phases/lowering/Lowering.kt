package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
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

    fun lowerPatches(patches: List<Patch>): List<FirPatch> {
        val mixinSourcePackageLCP = if (!options.disableLCP) {
            patches.map { it.classDeclaration.toXClassName().packageName }.reduceOrNull { lcp, next ->
                val currentParts = lcp.orEmpty().split('.')
                val nextParts = next.orEmpty().split('.')
                currentParts
                    .zip(nextParts)
                    .takeWhile { (current, next) -> current == next }
                    .joinToString(".") { it.first }
            }.orEmpty()
        } else null
        return patches.map { lowerPatch(it, mixinSourcePackageLCP) }
    }

    private fun lowerPatch(patch: Patch, mixinSourcePackageLCP: String?): FirPatch {
        val mixin = lowerMixin(patch, mixinSourcePackageLCP)
        val typeParameterResolver = patch.typeParameters.map { it.ksTypeParameter }.toTypeParameterResolver()
        return when (val classKind = patch.classKind) {
            is Patch.Class -> {
                val constructorParameters = classKind.constructorParameters.map {
                    lowerPatchClassConstructorParameter(it, typeParameterResolver)
                }
                FirPatchClass(
                    className = patch.classDeclaration.toXClassName(),
                    typeVariables = patch.typeParameters.toXTypeVariables(typeParameterResolver),
                    mixin = mixin,
                    impl = if (classKind.isAbstract) lowerPatchImpl(patch, mixin, constructorParameters) else null,
                    constructorParameters = constructorParameters,
                    initStrategy = patch.initStrategy,
                )
            }

            Patch.Interface -> FirPatchInterface(
                className = patch.classDeclaration.toXClassName(),
                typeVariables = patch.typeParameters.toXTypeVariables(typeParameterResolver),
                mixin = mixin,
            )
        }
    }

    private fun lowerPatchClassConstructorParameter(
        parameter: Patch.Class.ConstructorParameter,
        typeParameterResolver: TypeParameterResolver,
    ) = when (parameter) {
        is Patch.Class.ConstructorParameter.Origin -> FirPatchClass.ConstructorParameter.Origin(
            name = parameter.name,
            targetTypeCast = lowerTargetSubtypeCast(parameter.type, typeParameterResolver),
        )
    }

    private fun lowerPatchImpl(
        patch: Patch, mixin: IrMixin, constructorParameters: List<FirPatchClass.ConstructorParameter>,
    ): IrPatchImpl {
        val typeParameterResolver = patch.typeParameters.map { it.ksTypeParameter }
            .toTypeParameterResolver(TypeParameterResolver.EMPTY)
        return IrPatchImpl(
            patchOriginatingFile = patch.containingFile,
            className = patch.classDeclaration.toXClassName().withSuffix("_Impl"),
            typeVariables = patch.typeParameters.toXTypeVariables(typeParameterResolver),
            constructorParameters = buildList {
                constructorParameters.firstNotNullOfOrNull { it as? FirPatchClass.ConstructorParameter.Origin }?.let {
                    add(IrPatchImpl.ConstructorParameter.Instance(it.name, it.targetTypeCast))
                }
                if (mixin.duck != null && patch.shadowSources.isNotEmpty()) {
                    add(IrPatchImpl.ConstructorParameter.Duck(mixin.duck.className))
                }
            },
        )
    }

    private fun lowerMixin(patch: Patch, sourcePackageLCP: String?): IrMixin {
        val effectiveMixinAnnotations = if (patch.mixinAnnotations.isNotEmpty()) {
            lowerMixinAnnotations(patch.mixinAnnotations)
        } else {
            val targetClassValue = IrMixinAnnotation.Argument.ClassValue(patch.targetClassDeclaration.toXClassName())
            val valueArgument = IrMixinAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrMixinAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        }
        val typeParameterResolver = patch.typeParameters.map { it.ksTypeParameter }
            .toTypeParameterResolver(TypeParameterResolver.EMPTY)
        return IrMixin(
            patchOriginatingFile = patch.containingFile,
            className = resolveMixinClassName(patch.classDeclaration.toXClassName(), sourcePackageLCP),
            typeVariables = patch.typeParameters.toXTypeVariables(typeParameterResolver),
            side = patch.side,
            injections = buildList {
                addAll(patch.injections.map {
                    lowerMemberInjection(it, typeParameterResolver)
                })
                patch.companionObject?.let { companionObject ->
                    addAll(companionObject.injections.map {
                        lowerStaticInjection(companionObject, it, typeParameterResolver)
                    })
                }
            },
            duck = lowerMixinDuck(patch),
            annotations = effectiveMixinAnnotations,
        )
    }

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

    private fun lowerMixinDuck(patch: Patch): IrMixinDuck? {
        val typeParameterResolver = patch.typeParameters.map { it.ksTypeParameter }
            .toTypeParameterResolver(TypeParameterResolver.EMPTY)
        val shadows = patch.shadowSources.map {
            lowerMixinDuckShadowEntry(it, patch.classKind is Patch.Interface, typeParameterResolver)
        }
        val extensions = patch.extensionSources.map {
            lowerMixinDuckExtensionEntry(it, typeParameterResolver)
        }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                patchOriginatingFile = patch.containingFile,
                className = patch.classDeclaration.toXClassName().withSuffix("_Duck"),
                typeVariables = patch.typeParameters.toXTypeVariables(typeParameterResolver),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun lowerMixinDuckExtensionEntry(
        source: Patch.Extension,
        parentTypeParameterResolver: TypeParameterResolver,
    ): IrMixinDuck.Extension {
        val typeParameterResolver = source.typeParameters.map { it.ksTypeParameter }
            .toTypeParameterResolver(parentTypeParameterResolver)
        return when (source) {
            is Patch.Extension.Property -> IrMixinDuck.Extension.Property(
                typeName = source.type.toXTypeName(typeParameterResolver),
                sourceName = source.name,
                sourceGetterJvmName = source.getterJvmName,
                sourceSetterJvmName = source.setterJvmName,
                getterName = source.getterJvmName.withUniqueModPrefix(),
                setterName = source.setterJvmName?.withUniqueModPrefix(),
                receiverTargetTypeCast = lowerTargetSubtypeCast(source.receiverType, typeParameterResolver),
                typeVariables = source.typeParameters.toXTypeVariables(typeParameterResolver),
            )

            is Patch.Extension.Function -> IrMixinDuck.Extension.Function(
                sourceName = source.name,
                sourceJvmName = source.jvmName,
                name = source.jvmName.withUniqueModPrefix(),
                parameters = source.parameters.map {
                    IrFunctionParameter(
                        name = it.name,
                        typeName = it.type.toXTypeName(typeParameterResolver),
                    )
                },
                returnTypeName = source.returnType?.takeIf { !it.isUnit }?.toXTypeName(typeParameterResolver),
                receiverTargetTypeCast = lowerTargetSubtypeCast(source.receiverType, typeParameterResolver),
                typeVariables = source.typeParameters.toXTypeVariables(typeParameterResolver),
            )
        }
    }

    private fun lowerMixinDuckShadowEntry(
        source: Patch.Shadow,
        isInterface: Boolean,
        parentTypeParameterResolver: TypeParameterResolver,
    ): IrMixinDuck.Shadow {
        val typeParameterResolver = source.typeParameters.map { it.ksTypeParameter }
            .toTypeParameterResolver(parentTypeParameterResolver)
        return when (source) {
            is Patch.Shadow.Property -> {
                val effectiveMixinAnnotations = if (source.mixinAnnotations.isNotEmpty()) {
                    lowerMixinAnnotations(source.mixinAnnotations)
                } else {
                    listOfNotNull(
                        if (source.setterJvmName != null) options.mutableAnnotation else null,
                        if (FINAL in source.modifiers) options.finalAnnotation else null,
                        options.shadowAnnotation,
                    ).map { IrMixinAnnotation(poetesse.xClass(it), emptyList()) }
                }
                IrMixinDuck.Shadow.Property(
                    typeName = source.type.toXTypeName(typeParameterResolver),
                    sourceName = source.name,
                    sourceGetterJvmName = source.getterJvmName,
                    sourceSetterJvmName = source.setterJvmName,
                    getterName = source.getterJvmName.withUniqueModPrefix(),
                    setterName = source.setterJvmName?.withUniqueModPrefix(),
                    mappingName = source.mappingName,
                    modifiers = lowerShadowModifiers(source.modifiers, isInterface, isField = true),
                    mixinAnnotations = effectiveMixinAnnotations,
                    typeVariables = source.typeParameters.toXTypeVariables(typeParameterResolver),
                )
            }

            is Patch.Shadow.Function -> {
                val effectiveMixinAnnotations = if (source.mixinAnnotations.isNotEmpty()) {
                    lowerMixinAnnotations(source.mixinAnnotations)
                } else {
                    listOf(IrMixinAnnotation(poetesse.xClass(options.shadowAnnotation), emptyList()))
                }
                IrMixinDuck.Shadow.Function(
                    sourceName = source.name,
                    sourceJvmName = source.jvmName,
                    name = source.jvmName.withUniqueModPrefix(),
                    parameters = source.parameters.map {
                        IrFunctionParameter(
                            name = it.name,
                            typeName = it.type.toXTypeName(typeParameterResolver),
                        )
                    },
                    returnTypeName = source.returnType?.takeIf { !it.isUnit }?.toXTypeName(typeParameterResolver),
                    mappingName = source.mappingName,
                    modifiers = lowerShadowModifiers(source.modifiers, isInterface, isField = false),
                    mixinAnnotations = effectiveMixinAnnotations,
                    typeVariables = source.typeParameters.toXTypeVariables(typeParameterResolver),
                )
            }
        }
    }

    private fun lowerMemberInjection(
        injection: Patch.Injection,
        parentTypeParameterResolver: TypeParameterResolver,
    ): IrMixin.MemberInjection {
        val typeParameterResolver = injection.typeParameters.resolve(parentTypeParameterResolver)
        return IrMixin.MemberInjection(
            sourceJvmName = injection.jvmName,
            name = injection.jvmName.withUniqueModPrefix(),
            mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
            parameters = injection.parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.toXTypeName(typeParameterResolver),
                    lowerMixinAnnotations(parameter.mixinAnnotations),
                )
            },
            returnTypeName = injection.returnType?.takeIf { !it.isUnit }?.toXTypeName(typeParameterResolver),
            extensionReceiverTargetTypeCast = injection.extensionReceiverType?.let {
                lowerTargetSubtypeCast(it, typeParameterResolver)
            },
            typeVariables = injection.typeParameters.toXTypeVariables(typeParameterResolver),
        )
    }

    private fun lowerStaticInjection(
        companionObject: Patch.CompanionObject,
        injection: Patch.Injection,
        parentTypeParameterResolver: TypeParameterResolver,
    ): IrMixin.StaticInjection {
        val typeParameterResolver = injection.typeParameters.resolve(parentTypeParameterResolver)
        return IrMixin.StaticInjection(
            sourceJvmName = injection.jvmName,
            name = injection.jvmName.withUniqueModPrefix(),
            mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
            parameters = injection.parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.toXTypeName(typeParameterResolver),
                    lowerMixinAnnotations(parameter.mixinAnnotations),
                )
            },
            returnTypeName = injection.returnType?.takeIf { !it.isUnit }?.toXTypeName(typeParameterResolver),
            patchCompanionObjectName = companionObject.name,
            typeVariables = injection.typeParameters.toXTypeVariables(typeParameterResolver),
        )
    }

    private fun lowerMixinAnnotations(annotations: List<MixinAnnotation>) = annotations.map(::lowerMixinAnnotation)

    private fun lowerMixinAnnotation(annotation: MixinAnnotation) = IrMixinAnnotation(
        className = annotation.typeClassDeclaration.toXClassName(),
        arguments = annotation.arguments.map { argument ->
            when (argument) {
                is MixinAnnotation.ScalarArgument -> IrMixinAnnotation.ScalarArgument(
                    name = argument.name,
                    value = lowerMixinAnnotationArgumentValue(argument.value),
                )

                is MixinAnnotation.ArrayArgument -> IrMixinAnnotation.ArrayArgument(
                    name = argument.name,
                    elements = argument.elements.map { lowerMixinAnnotationArgumentValue(it) },
                )
            }
        },
    )

    private fun lowerMixinAnnotationArgumentValue(
        value: MixinAnnotation.Argument.Value
    ): IrMixinAnnotation.Argument.Value = when (value) {
        is MixinAnnotation.Argument.BooleanValue -> IrMixinAnnotation.Argument.BooleanValue(value.boolean)
        is MixinAnnotation.Argument.ByteValue -> IrMixinAnnotation.Argument.ByteValue(value.byte)
        is MixinAnnotation.Argument.ShortValue -> IrMixinAnnotation.Argument.ShortValue(value.short)
        is MixinAnnotation.Argument.IntValue -> IrMixinAnnotation.Argument.IntValue(value.int)
        is MixinAnnotation.Argument.LongValue -> IrMixinAnnotation.Argument.LongValue(value.long)
        is MixinAnnotation.Argument.CharValue -> IrMixinAnnotation.Argument.CharValue(value.char)
        is MixinAnnotation.Argument.FloatValue -> IrMixinAnnotation.Argument.FloatValue(value.float)
        is MixinAnnotation.Argument.DoubleValue -> IrMixinAnnotation.Argument.DoubleValue(value.double)
        is MixinAnnotation.Argument.StringValue -> IrMixinAnnotation.Argument.StringValue(value.string)
        is MixinAnnotation.Argument.ClassValue -> {
            IrMixinAnnotation.Argument.ClassValue(value.classDeclaration.toXClassName())
        }

        is MixinAnnotation.Argument.EnumValue -> {
            IrMixinAnnotation.Argument.EnumValue(value.enumClassDeclaration.toXClassName(), value.entryName)
        }

        is MixinAnnotation.Argument.AnnotationValue -> {
            IrMixinAnnotation.Argument.AnnotationValue(lowerMixinAnnotation(value.annotation))
        }
    }

    private fun lowerTargetSubtypeCast(targetSubtype: Type, typeParameterResolver: TypeParameterResolver) =
        IrTargetSubtypeCast(
            typeName = targetSubtype.toXTypeName(typeParameterResolver),
            isUnsafeCastRequired = !targetSubtype.isInterface,
            isTargetCastRequired = !targetSubtype.isAny,
        )

    private fun lowerShadowModifiers(
        modifiers: EnumSet<Modifier>,
        isInterface: Boolean,
        isField: Boolean,
    ): List<JPModifier> {
        val result = EnumSet.copyOf(modifiers)
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

    private fun Type.toXTypeName(typeParameterResolver: TypeParameterResolver): XTypeName =
        poetesse.xType(ksType.toTypeName(typeParameterResolver))

    private fun List<TypeParameter>.resolve(parent: TypeParameterResolver): TypeParameterResolver =
        map { it.ksTypeParameter }.toTypeParameterResolver(parent)

    private fun List<TypeParameter>.toXTypeVariables(resolver: TypeParameterResolver): List<XTypeVariableName> =
        map { typeParameter ->
            poetesse.xTypeVariable(typeParameter.name, typeParameter.bounds.map { it.toXTypeName(resolver) })
        }

    private fun KSClassDeclaration.toXClassName(): XClassName =
        poetesse.xClass(toClassName())
}
