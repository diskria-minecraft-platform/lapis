package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.diskria.lapis.ksp.common.JavaModifiers
import io.github.diskria.lapis.ksp.kspPoetesse
import io.github.diskria.lapis.ksp.logging.KspArguments
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.*
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.interop.xClass
import io.github.diskria.poetesse.interop.xType
import io.github.diskria.poetesse.java.JPModifier

class Lowering(
    private val kspArguments: KspArguments,
    @Suppress("unused") private val logger: Logger,
) {
    fun lower(result: ValidatorResult): IrResult {
        val mixinSourcePackageLCP = if (kspArguments.disableLCP) null else {
            findMixinSourcePackageLCP(result.patches)
        }
        return IrResult(result.patches.map { lowerPatch(it, mixinSourcePackageLCP) })
    }

    private fun lowerPatch(patch: Patch, mixinSourcePackageLCP: String?): IrPatch {
        val constructorArguments = patch.constructorParameters.map(::lowerPatchConstructorArgument)
        val mixin = lowerMixin(patch, mixinSourcePackageLCP)
        return IrPatch(
            className = patch.className,
            constructorArguments = constructorArguments,
            impl = lowerPatchImpl(patch, mixin, constructorArguments),
            mixin = mixin,
        )
    }

    private fun lowerPatchConstructorArgument(parameter: PatchConstructorParameter): IrPatchConstructorArgument =
        when (parameter) {
            is PatchConstructorOriginParameter -> {
                IrPatchConstructorOriginArgument(parameter.typeClassDeclaration.toXClassName())
            }
        }

    private fun lowerPatchImpl(
        patch: Patch,
        mixin: IrMixin,
        constructorArguments: List<IrPatchConstructorArgument>,
    ): IrPatchImpl? =
        if (patch.isImplRequired) {
            IrPatchImpl(
                originatingFiles = listOfNotNull(patch.containingFile),
                className = patch.className.withSuffix("_Impl"),
                constructorParameters = buildList {
                    constructorArguments.filterIsInstance<IrPatchConstructorOriginArgument>().firstOrNull()?.let {
                        add(IrPatchImplConstructorInstanceParameter(it.className))
                    }
                    if (patch.shadowSources.isNotEmpty()) {
                        add(IrPatchImplConstructorDuckParameter(requireNotNull(mixin.duck).className))
                    }
                },
                initStrategy = patch.initStrategy,
            )
        } else null

    private fun lowerMixin(patch: Patch, sourcePackageLCP: String?): IrMixin =
        IrMixin(
            originatingFiles = listOfNotNull(patch.containingFile),
            className = resolveMixinClassName(patch.className, sourcePackageLCP),
            env = patch.env,
            injections = patch.injections.map(::lowerInjection),
            duck = lowerMixinDuck(patch),
            targetClassName = patch.targetClassDeclaration?.toXClassName(),
            annotations = patch.mixinAnnotations.map(::lowerMixinAnnotation),
        )

    private fun lowerMixinDuck(patch: Patch): IrMixinDuck? {
        val entries = patch.extensionSources.map(::lowerMixinDuckExtensionEntry) +
            patch.shadowSources.map(::lowerMixinDuckShadowEntry)
        return if (entries.isNotEmpty()) {
            IrMixinDuck(
                originatingFiles = listOfNotNull(patch.containingFile),
                className = patch.className.withSuffix("_Duck"),
                entries = entries,
            )
        } else null
    }

    private fun lowerMixinDuckExtensionEntry(source: PatchExtensionSource): IrMixinDuckExtensionEntry =
        when (source) {
            is ExtensionProperty -> {
                IrMixinDuckExtensionProperty(
                    typeName = source.typeName,
                    sourceName = source.name,
                    sourceGetterJvmName = source.getterJvmName,
                    sourceSetterJvmName = source.setterJvmName,
                    getterName = source.getterJvmName.withUniqueModPrefix(),
                    setterName = source.setterJvmName?.withUniqueModPrefix(),
                    receiverTypeName = source.receiverClassDeclaration.toXClassName(),
                )
            }

            is ExtensionFunction -> {
                IrMixinDuckExtensionFunction(
                    sourceName = source.name,
                    sourceJvmName = source.jvmName,
                    name = source.jvmName.withUniqueModPrefix(),
                    parameters = source.parameters.map { it.asIrParameter() },
                    returnTypeName = source.returnTypeName,
                    receiverTypeName = source.receiverClassDeclaration.toXClassName(),
                )
            }
        }

    private fun lowerMixinDuckShadowEntry(source: PatchShadowSource): IrMixinShadowEntry =
        when (source) {
            is ShadowProperty -> {
                IrMixinShadowProperty(
                    typeName = source.typeName,
                    sourceName = source.name,
                    sourceGetterJvmName = source.getterJvmName,
                    sourceSetterJvmName = source.setterJvmName,
                    getterName = source.getterJvmName.withUniqueModPrefix(),
                    setterName = source.setterJvmName?.withUniqueModPrefix(),
                    mappingName = source.mappingName,
                    modifiers = source.modifiers.toMutableSet().apply { remove(JPModifier.FINAL) },
                    isFinal = JPModifier.FINAL in source.modifiers,
                    mixinAnnotations = source.mixinAnnotations.map(::lowerMixinAnnotation),
                )
            }

            is ShadowFunction -> {
                IrMixinShadowFunction(
                    sourceName = source.name,
                    sourceJvmName = source.jvmName,
                    name = source.jvmName.withUniqueModPrefix(),
                    parameters = source.parameters.map { it.asIrParameter() },
                    returnTypeName = source.returnTypeName,
                    mappingName = source.mappingName,
                    mixinAnnotations = source.mixinAnnotations.map(::lowerMixinAnnotation),
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
                    },
                )
            }
        }

    private fun lowerInjection(injection: PatchInjection): IrInjection =
        IrInjection(
            jvmName = injection.jvmName,
            extensionReceiverClassName = injection.extensionReceiverClassName,
            mixinAnnotations = injection.mixinAnnotations.map(::lowerMixinAnnotation),
            isStatic = injection.isStatic,
            parameters = injection.parameters.map { parameter ->
                IrNativeInjectionParameter(
                    parameter.name,
                    parameter.type.toXTypeName(),
                    parameter.mixinAnnotations.map(::lowerMixinAnnotation),
                )
            },
            returnTypeName = injection.returnType?.toXTypeName(),
        )

    private fun resolveMixinClassName(sourceClassName: XClassName, sourcePackageLCP: String?): XClassName {
        val sourcePackageName = sourceClassName.packageName
        val mixinPackageName = buildString {
            append(kspArguments.mixinPackage)
            kspArguments.mixinGeneratedSubpackage?.let { append(".$it") }
            if (sourcePackageName != null && sourcePackageLCP != null && sourcePackageName != sourcePackageLCP) {
                append(".${sourcePackageName.removePrefix("$sourcePackageLCP.")}")
            }
        }
        return kspPoetesse.xClass(mixinPackageName, sourceClassName.simpleName).withSuffix("_Generated")
    }

    private fun lowerMixinAnnotation(annotation: MixinAnnotation): IrMixinAnnotation {
        fun MixinAnnotationArgumentValue.lowerValue(): IrMixinAnnotationArgumentValue = when (this) {
            is MixinAnnotationBooleanArgumentValue -> IrMixinAnnotationBooleanArgumentValue(boolean)
            is MixinAnnotationByteArgumentValue -> IrMixinAnnotationByteArgumentValue(byte)
            is MixinAnnotationShortArgumentValue -> IrMixinAnnotationShortArgumentValue(short)
            is MixinAnnotationIntArgumentValue -> IrMixinAnnotationIntArgumentValue(int)
            is MixinAnnotationLongArgumentValue -> IrMixinAnnotationLongArgumentValue(long)
            is MixinAnnotationCharArgumentValue -> IrMixinAnnotationCharArgumentValue(char)
            is MixinAnnotationFloatArgumentValue -> IrMixinAnnotationFloatArgumentValue(float)
            is MixinAnnotationDoubleArgumentValue -> IrMixinAnnotationDoubleArgumentValue(double)
            is MixinAnnotationStringArgumentValue -> IrMixinAnnotationStringArgumentValue(string)
            is MixinAnnotationClassTypeArgumentValue -> IrMixinAnnotationClassTypeArgumentValue(typeName)
            is MixinAnnotationEnumArgumentValue -> IrMixinAnnotationEnumArgumentValue(enumClassName, entryName)
            is MixinAnnotationEmbeddedAnnotationArgumentValue -> {
                IrMixinAnnotationEmbeddedAnnotationArgumentValue(lowerMixinAnnotation(embeddedAnnotation))
            }
        }
        return IrMixinAnnotation(annotation.className, annotation.arguments.map { argument ->
            when (argument) {
                is MixinAnnotationSingleArgument -> {
                    IrMixinAnnotationSingleArgument(argument.name, argument.value.lowerValue())
                }

                is MixinAnnotationArrayArgument -> {
                    IrMixinAnnotationArrayArgument(argument.name, argument.values.map { it.lowerValue() })
                }
            }
        })
    }

    private fun findMixinSourcePackageLCP(sources: List<SourceFile>): String =
        sources.map { it.className.packageName }.reduceOrNull { lcp, next ->
            val currentParts = lcp.orEmpty().split('.')
            val nextParts = next.orEmpty().split('.')
            currentParts.zip(nextParts).takeWhile { (current, next) -> current == next }.joinToString(".") { it.first }
        }.orEmpty()

    private fun String.withUniqueModPrefix(): String =
        kspArguments.uniqueModPrefix + this
}

fun KSType.toXTypeName(): XTypeName =
    kspPoetesse.xType(toTypeName())

fun KSClassDeclaration.toXClassName(): XClassName =
    kspPoetesse.xClass(toClassName())

fun XClassName.withSuffix(name: String): XClassName =
    kspPoetesse.xClass(packageName, "$simpleName$name")
