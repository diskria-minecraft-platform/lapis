package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.Type
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class Lowering(private val options: KspOptions, private val poetesse: PoetesseScope) {

    fun lowerPatches(patches: List<Patch>): List<IrPatch> {
        val mixinSourcePackageLCP = if (options.disableLCP) null else {
            findMixinSourcePackageLCP(patches)
        }
        return patches.map { lowerPatch(it, mixinSourcePackageLCP) }
    }

    private fun lowerPatch(patch: Patch, mixinSourcePackageLCP: String?): IrPatch {
        val mixin = lowerMixin(patch, mixinSourcePackageLCP)
        return when (val classKind = patch.classKind) {
            is Patch.Class -> {
                val constructorParameters = classKind.constructorParameters.map(::lowerPatchClassConstructorParameter)
                IrPatchClass(
                    className = patch.classDeclaration.toXClassName(),
                    mixin = mixin,
                    impl = if (classKind.isAbstract) lowerPatchImpl(patch, mixin, constructorParameters) else null,
                    constructorParameters = constructorParameters,
                    initStrategy = patch.initStrategy
                )
            }

            Patch.Interface -> IrPatchInterface(
                className = patch.classDeclaration.toXClassName(),
                mixin = mixin,
            )
        }
    }

    private fun lowerPatchClassConstructorParameter(parameter: Patch.Class.ConstructorParameter) = when (parameter) {
        is Patch.Class.ConstructorParameter.Origin -> IrPatchClass.ConstructorParameter.Origin(
            name = parameter.name,
            targetTypeCast = lowerTargetTypeCast(parameter.type),
        )
    }

    private fun lowerPatchImpl(
        patch: Patch, mixin: IrMixin, constructorParameters: List<IrPatchClass.ConstructorParameter>,
    ) = IrPatchImpl(
        patchOriginatingFile = patch.containingFile,
        className = patch.classDeclaration.toXClassName().withSuffix("_Impl"),
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? IrPatchClass.ConstructorParameter.Origin }?.let {
                add(IrPatchImpl.ConstructorParameter.Instance(it.name, it.targetTypeCast))
            }
            if (mixin.duck != null && patch.shadowSources.isNotEmpty()) {
                add(IrPatchImpl.ConstructorParameter.Duck(mixin.duck.className))
            }
        },
    )

    private fun lowerMixin(patch: Patch, sourcePackageLCP: String?): IrMixin {
        val effectiveMixinAnnotations = if (patch.mixinAnnotations.isNotEmpty()) {
            lowerMixinAnnotations(patch.mixinAnnotations)
        } else {
            val targetTypeValue = IrMixinAnnotation.Argument.TypeValue(patch.targetType.toXTypeName())
            val valueArgument = IrMixinAnnotation.ScalarArgument("value", targetTypeValue)
            listOf(IrMixinAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        }
        return IrMixin(
            patchOriginatingFile = patch.containingFile,
            className = resolveMixinClassName(patch.classDeclaration.toXClassName(), sourcePackageLCP),
            env = patch.env,
            injections = buildList {
                addAll(patch.injections.map(::lowerMemberInjection))
                patch.companionObject?.let { companionObject ->
                    addAll(companionObject.injections.map { lowerStaticInjection(companionObject, it) })
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
        val shadows = patch.shadowSources.map { lowerMixinDuckShadowEntry(it, patch.classKind is Patch.Interface) }
        val extensions = patch.extensionSources.map { lowerMixinDuckExtensionEntry(it) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                patchOriginatingFile = patch.containingFile,
                className = patch.classDeclaration.toXClassName().withSuffix("_Duck"),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun lowerMixinDuckExtensionEntry(source: Patch.Extension) = when (source) {
        is Patch.Extension.Property -> IrMixinDuck.Extension.Property(
            typeName = source.type.toXTypeName(),
            sourceName = source.name,
            sourceGetterJvmName = source.getterJvmName,
            sourceSetterJvmName = source.setterJvmName,
            getterName = source.getterJvmName.withUniqueModPrefix(),
            setterName = source.setterJvmName?.withUniqueModPrefix(),
            receiverTargetTypeCast = lowerTargetTypeCast(source.receiverType),
        )

        is Patch.Extension.Function -> IrMixinDuck.Extension.Function(
            sourceName = source.name,
            sourceJvmName = source.jvmName,
            name = source.jvmName.withUniqueModPrefix(),
            parameters = source.parameters.map { IrFunctionParameter(it.name, it.type.toXTypeName()) },
            returnTypeName = source.returnType?.takeIf { !it.isUnit }?.toXTypeName(),
            receiverTargetTypeCast = lowerTargetTypeCast(source.receiverType),
        )
    }

    private fun lowerMixinDuckShadowEntry(source: Patch.Shadow, isInterface: Boolean) = when (source) {
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
                typeName = source.type.toXTypeName(),
                sourceName = source.name,
                sourceGetterJvmName = source.getterJvmName,
                sourceSetterJvmName = source.setterJvmName,
                getterName = source.getterJvmName.withUniqueModPrefix(),
                setterName = source.setterJvmName?.withUniqueModPrefix(),
                mappingName = source.mappingName,
                modifiers = lowerShadowModifiers(source.modifiers, isInterface, isField = true),
                mixinAnnotations = effectiveMixinAnnotations,
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
                parameters = source.parameters.map { IrFunctionParameter(it.name, it.type.toXTypeName()) },
                returnTypeName = source.returnType?.takeIf { !it.isUnit }?.toXTypeName(),
                mappingName = source.mappingName,
                modifiers = lowerShadowModifiers(source.modifiers, isInterface, isField = false),
                mixinAnnotations = effectiveMixinAnnotations,
            )
        }
    }

    private fun lowerMemberInjection(injection: Patch.Injection) = IrMixin.MemberInjection(
        sourceJvmName = injection.jvmName,
        name = injection.jvmName.withUniqueModPrefix(),
        mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
        parameters = injection.parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.type.toXTypeName(),
                lowerMixinAnnotations(parameter.mixinAnnotations),
            )
        },
        returnTypeName = injection.returnType?.takeIf { !it.isUnit }?.takeIf { !it.isUnit }?.toXTypeName(),
        extensionReceiverTargetTypeCast = injection.extensionReceiverType?.let { lowerTargetTypeCast(it) },
    )

    private fun lowerStaticInjection(
        companionObject: Patch.CompanionObject,
        injection: Patch.Injection,
    ) = IrMixin.StaticInjection(
        sourceJvmName = injection.jvmName,
        name = injection.jvmName.withUniqueModPrefix(),
        mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
        parameters = injection.parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.type.toXTypeName(),
                lowerMixinAnnotations(parameter.mixinAnnotations),
            )
        },
        returnTypeName = injection.returnType?.takeIf { !it.isUnit }?.toXTypeName(),
        patchCompanionObjectName = companionObject.name,
    )

    private fun lowerMixinAnnotations(annotations: List<MixinAnnotation>) = annotations.map(::lowerMixinAnnotation)

    private fun lowerMixinAnnotation(annotation: MixinAnnotation) = IrMixinAnnotation(
        typeClassName = annotation.typeClassDeclaration.toXClassName(),
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
        is MixinAnnotation.Argument.TypeValue -> IrMixinAnnotation.Argument.TypeValue(value.type.toXTypeName())
        is MixinAnnotation.Argument.EnumValue -> IrMixinAnnotation.Argument.EnumValue(
            value.classDeclaration.toXClassName(), value.entryName
        )

        is MixinAnnotation.Argument.AnnotationValue -> {
            IrMixinAnnotation.Argument.AnnotationValue(lowerMixinAnnotation(value.annotation))
        }
    }

    private fun lowerTargetTypeCast(targetCompatibleType: Type) = IrTargetTypeCast(
        typeName = targetCompatibleType.toXTypeName(),
        isUnsafeCastRequired = !targetCompatibleType.isInterface,
        isTargetCastRequired = !targetCompatibleType.isAny,
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

    private fun findMixinSourcePackageLCP(patches: List<Patch>): String =
        patches.map { it.classDeclaration.toXClassName().packageName }.reduceOrNull { lcp, next ->
            val currentParts = lcp.orEmpty().split('.')
            val nextParts = next.orEmpty().split('.')
            currentParts.zip(nextParts).takeWhile { (current, next) -> current == next }.joinToString(".") { it.first }
        }.orEmpty()

    private fun String.withUniqueModPrefix(): String =
        options.uniqueModPrefix + this

    private fun Type.toXTypeName(): XTypeName =
        poetesse.xType(type.toTypeName())

    private fun KSClassDeclaration.toXClassName(): XClassName =
        poetesse.xClass(toClassName())
}
