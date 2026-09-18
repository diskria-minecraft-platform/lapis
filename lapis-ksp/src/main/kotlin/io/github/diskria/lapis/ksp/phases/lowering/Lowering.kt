package io.github.diskria.lapis.ksp.phases.lowering

import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.kspPoetesse
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Patch
import io.github.diskria.lapis.ksp.phases.validator.models.TargetType
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.xClass
import io.github.diskria.poetesse.java.JPModifier

class Lowering(
    private val kspOptions: KspOptions,
    @Suppress("unused") private val logger: Logger,
) {
    fun lower(patches: List<Patch>): List<IrPatch> {
        val mixinSourcePackageLCP = if (kspOptions.disableLCP) null else {
            findMixinSourcePackageLCP(patches)
        }
        return patches.map { lowerPatch(it, mixinSourcePackageLCP) }
    }

    private fun lowerPatch(patch: Patch, mixinSourcePackageLCP: String?): IrPatch {
        val mixin = lowerMixin(patch, mixinSourcePackageLCP)
        return when (val classKind = patch.classKind) {
            is Patch.Class -> {
                val constructorParameters = classKind.constructorParameters.map(::lowerPatchConstructorParameter)
                IrPatchClass(
                    className = patch.className,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) lowerPatchImpl(patch, mixin, constructorParameters) else null,
                    constructorParameters = constructorParameters,
                    initStrategy = patch.initStrategy
                )
            }

            Patch.Interface -> IrPatchInterface(
                className = patch.className,
                mixin = mixin,
            )
        }
    }

    private fun lowerPatchConstructorParameter(parameter: Patch.Class.ConstructorParameter) = when (parameter) {
        is Patch.Class.ConstructorParameter.Origin -> IrPatchClass.ConstructorParameter.Origin(
            name = parameter.name,
            type = lowerTargetType(parameter.type),
        )
    }

    private fun lowerPatchImpl(
        patch: Patch, mixin: IrMixin, constructorParameters: List<IrPatchClass.ConstructorParameter>,
    ) = IrPatchImpl(
        patchOriginatingFile = patch.containingFile,
        className = patch.className.withSuffix("_Impl"),
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? IrPatchClass.ConstructorParameter.Origin }?.let {
                add(IrPatchImpl.ConstructorParameter.Instance(it.name, it.type))
            }
            if (mixin.duck != null && patch.duckSources.filterIsInstance<Patch.Shadow>().isNotEmpty()) {
                add(IrPatchImpl.ConstructorParameter.Duck(mixin.duck.className))
            }
        },
    )

    private fun lowerMixin(patch: Patch, sourcePackageLCP: String?) = IrMixin(
        patchOriginatingFile = patch.containingFile,
        className = resolveMixinClassName(patch.className, sourcePackageLCP),
        env = patch.env,
        injections = buildList {
            addAll(patch.injections.map(::lowerMemberInjection))
            patch.companionObject?.let { companionObject ->
                addAll(companionObject.injections.map { lowerStaticInjection(companionObject, it) })
            }
        },
        duck = lowerMixinDuck(patch),
        targetTypeName = patch.targetTypeName,
        annotations = lowerMixinAnnotations(patch.mixinAnnotations),
    )

    private fun resolveMixinClassName(sourceClassName: XClassName, sourcePackageLCP: String?): XClassName {
        val sourcePackageName = sourceClassName.packageName
        val mixinPackageName = buildString {
            append(kspOptions.mixinPackage)
            kspOptions.mixinGeneratedSubpackage?.let { append(".$it") }
            if (sourcePackageName != null && sourcePackageLCP != null && sourcePackageName != sourcePackageLCP) {
                append(".${sourcePackageName.removePrefix("$sourcePackageLCP.")}")
            }
        }
        return kspPoetesse.xClass(mixinPackageName, sourceClassName.simpleName).withSuffix("_Generated")
    }

    private fun lowerMixinDuck(patch: Patch): IrMixinDuck? {
        val entries = patch.duckSources.map { duckSource ->
            when (duckSource) {
                is Patch.Extension -> lowerMixinDuckExtensionEntry(duckSource)
                is Patch.Shadow -> lowerMixinDuckShadowEntry(duckSource)
            }
        }
        return if (entries.isNotEmpty()) {
            IrMixinDuck(
                patchOriginatingFile = patch.containingFile,
                className = patch.className.withSuffix("_Duck"),
                entries = entries,
            )
        } else null
    }

    private fun lowerMixinDuckExtensionEntry(source: Patch.Extension) = when (source) {
        is Patch.Extension.Property -> IrMixinDuck.Extension.Property(
            typeName = source.typeName,
            sourceName = source.name,
            sourceGetterJvmName = source.getterJvmName,
            sourceSetterJvmName = source.setterJvmName,
            getterName = source.getterJvmName.withUniqueModPrefix(),
            setterName = source.setterJvmName?.withUniqueModPrefix(),
            receiverType = lowerTargetType(source.receiverType),
        )

        is Patch.Extension.Function -> IrMixinDuck.Extension.Function(
            sourceName = source.name,
            sourceJvmName = source.jvmName,
            name = source.jvmName.withUniqueModPrefix(),
            parameters = source.parameters.map { it.asIrFunctionParameter() },
            returnTypeName = source.returnTypeName,
            receiverType = lowerTargetType(source.receiverType),
        )
    }

    private fun lowerMixinDuckShadowEntry(source: Patch.Shadow) = when (source) {
        is Patch.Shadow.Property -> IrMixinDuck.Shadow.Property(
            typeName = source.typeName,
            sourceName = source.name,
            sourceGetterJvmName = source.getterJvmName,
            sourceSetterJvmName = source.setterJvmName,
            getterName = source.getterJvmName.withUniqueModPrefix(),
            setterName = source.setterJvmName?.withUniqueModPrefix(),
            mappingName = source.mappingName,
            modifiers = source.modifiers.toMutableSet().apply { remove(JPModifier.FINAL) },
            isFinal = JPModifier.FINAL in source.modifiers,
            mixinAnnotations = lowerMixinAnnotations(source.mixinAnnotations),
        )

        is Patch.Shadow.Function -> IrMixinDuck.Shadow.Function(
            sourceName = source.name,
            sourceJvmName = source.jvmName,
            name = source.jvmName.withUniqueModPrefix(),
            parameters = source.parameters.map { it.asIrFunctionParameter() },
            returnTypeName = source.returnTypeName,
            mappingName = source.mappingName,
            mixinAnnotations = lowerMixinAnnotations(source.mixinAnnotations),
            modifiers = if (JPModifier.STATIC in source.modifiers) source.modifiers else buildSet {
                add(JPModifier.ABSTRACT)
                addAll(source.modifiers.mapNotNull { modifier ->
                    if (modifier == JPModifier.PRIVATE) {
                        return@mapNotNull JPModifier.PROTECTED
                    }
                    if (modifier in JavaModifiers.abstractIllegals) {
                        return@mapNotNull null
                    }
                    modifier
                })
            }
        )
    }

    private fun lowerMemberInjection(injection: Patch.Injection) = IrMixin.MemberInjection(
        jvmName = injection.jvmName,
        mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
        parameters = injection.parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.typeName,
                lowerMixinAnnotations(parameter.mixinAnnotations),
            )
        },
        returnTypeName = injection.returnTypeName,
        extensionReceiverType = injection.extensionReceiverType?.let { lowerTargetType(it) },
    )

    private fun lowerStaticInjection(
        companionObject: Patch.CompanionObject,
        injection: Patch.Injection,
    ) = IrMixin.StaticInjection(
        jvmName = injection.jvmName,
        mixinAnnotations = lowerMixinAnnotations(injection.mixinAnnotations),
        parameters = injection.parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.typeName,
                lowerMixinAnnotations(parameter.mixinAnnotations),
            )
        },
        returnTypeName = injection.returnTypeName,
        patchCompanionName = companionObject.name,
    )

    private fun lowerMixinAnnotations(annotations: List<MixinAnnotation>) = annotations.map(::lowerMixinAnnotation)

    private fun lowerMixinAnnotation(annotation: MixinAnnotation) = IrMixinAnnotation(
        typeClassName = annotation.typeClassName,
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
        is MixinAnnotation.Argument.TypeValue -> IrMixinAnnotation.Argument.TypeValue(value.typeName)
        is MixinAnnotation.Argument.EnumValue -> IrMixinAnnotation.Argument.EnumValue(value.className, value.entryName)
        is MixinAnnotation.Argument.AnnotationValue -> {
            IrMixinAnnotation.Argument.AnnotationValue(lowerMixinAnnotation(value.annotation))
        }
    }

    private fun lowerTargetType(targetType: TargetType) = IrTargetType(
        typeName = targetType.typeName,
        isObjectCastRequired = !targetType.isInterface,
        isTargetTypeCastRequired = !targetType.isAny,
    )

    private fun findMixinSourcePackageLCP(patches: List<Patch>): String =
        patches.map { it.className.packageName }.reduceOrNull { lcp, next ->
            val currentParts = lcp.orEmpty().split('.')
            val nextParts = next.orEmpty().split('.')
            currentParts.zip(nextParts).takeWhile { (current, next) -> current == next }.joinToString(".") { it.first }
        }.orEmpty()

    private fun String.withUniqueModPrefix(): String =
        kspOptions.uniqueModPrefix + this
}

fun XClassName.withSuffix(name: String): XClassName =
    kspPoetesse.xClass(packageName, "$simpleName$name")
