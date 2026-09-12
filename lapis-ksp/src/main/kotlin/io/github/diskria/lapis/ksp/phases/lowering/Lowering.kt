package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.diskria.lapis.ksp.common.JavaModifiers
import io.github.diskria.lapis.ksp.logging.KspArguments
import io.github.diskria.lapis.ksp.logging.Logger
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.lowering.models.common.*
import io.github.diskria.lapis.ksp.phases.lowering.types.*
import io.github.diskria.lapis.ksp.phases.validator.models.ValidatorResult
import io.github.diskria.lapis.ksp.phases.validator.models.common.*
import io.github.diskria.lapis.ksp.phases.validator.models.patches.*
import io.github.diskria.poetesse.java.JPModifier
import io.github.diskria.poetesse.kotlin.*
import kotlin.reflect.KClass

class Lowering(
    private val kspArguments: KspArguments,
    @Suppress("unused") private val logger: Logger,
) {
    private val patches: MutableList<IrPatch> = mutableListOf()

    fun lower(result: ValidatorResult): IrResult {
        val mixinSourcePackageLCP = if (kspArguments.disableLCP) null else {
            findMixinSourcePackageLCP(result.patches)
        }
        patches += result.patches.map { lowerPatch(it, mixinSourcePackageLCP) }
        return IrResult(
            patches = patches,
        )
    }

    private fun lowerPatch(patch: Patch, mixinSourcePackageLCP: String?): IrPatch {
        val constructorArguments = patch.constructorParameters.map(::lowerPatchConstructorArgument)
        return IrPatch(
            className = patch.className,
            constructorArguments = constructorArguments,
            impl = lowerPatchImpl(patch, constructorArguments),
            mixin = lowerMixin(patch, mixinSourcePackageLCP),
        )
    }

    private fun lowerPatchConstructorArgument(parameter: PatchConstructorParameter): IrPatchConstructorArgument =
        when (parameter) {
            is PatchConstructorOriginParameter -> {
                IrPatchConstructorOriginArgument(parameter.typeClassDeclaration.asIrClassName())
            }
        }

    private fun lowerPatchImpl(patch: Patch, constructorArguments: List<IrPatchConstructorArgument>): IrPatchImpl? =
        if (patch.isImplRequired) {
            IrPatchImpl(
                originatingFiles = listOfNotNull(patch.containingFile),
                className = patch.className.derived("Impl"),
                constructorParameters = buildList {
                    constructorArguments.filterIsInstance<IrPatchConstructorOriginArgument>().firstOrNull()?.let {
                        add(IrPatchImplConstructorInstanceParameter(it.className))
                    }
                    if (patch.shadowSources.isNotEmpty()) {
                        add(IrPatchImplConstructorDuckInterfaceParameter)
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
            injections = patch.injections.flatMap(::lowerInjections),
            duckInterface = lowerMixinDuckInterface(patch),
            targetClassName = patch.targetClassDeclaration?.asIrClassName(),
            mixinAnnotations = patch.mixinAnnotations.map(::lowerMixinAnnotation),
        )

    private fun lowerMixinDuckInterface(patch: Patch): IrMixinDuckInterface? {
        val entries = patch.extensionSources.map(::lowerMixinDuckExtensionEntry) +
            patch.shadowSources.map(::lowerMixinDuckShadowEntry)
        return if (entries.isNotEmpty()) {
            IrMixinDuckInterface(
                originatingFiles = listOfNotNull(patch.containingFile),
                className = patch.className.derived("Duck"),
                entries = entries,
            )
        } else null
    }

    private fun lowerMixinDuckExtensionEntry(source: PatchExtensionSource): IrMixinDuckExtensionEntry =
        when (source) {
            is ExtensionProperty -> with(source) {
                IrMixinDuckExtensionProperty(
                    typeName = typeName,
                    sourceName = name,
                    sourceGetterJvmName = getterJvmName,
                    sourceSetterJvmName = setterJvmName,
                    getterName = getterJvmName.withUniqueModPrefix(),
                    setterName = setterJvmName?.withUniqueModPrefix(),
                    receiverTypeName = receiverClassDeclaration.asIrClassName(),
                )
            }

            is ExtensionFunction -> with(source) {
                IrMixinDuckExtensionFunction(
                    sourceName = name,
                    sourceJvmName = jvmName,
                    name = jvmName.withUniqueModPrefix(),
                    parameters = parameters.map { it.asIrParameter() },
                    returnTypeName = returnTypeName,
                    receiverTypeName = receiverClassDeclaration.asIrClassName(),
                )
            }
        }

    private fun lowerMixinDuckShadowEntry(source: PatchShadowSource): IrMixinShadowEntry =
        when (source) {
            is ShadowProperty -> with(source) {
                IrMixinShadowProperty(
                    typeName = typeName,
                    sourceName = name,
                    sourceGetterJvmName = getterJvmName,
                    sourceSetterJvmName = setterJvmName,
                    getterName = getterJvmName.withUniqueModPrefix(),
                    setterName = setterJvmName?.withUniqueModPrefix(),
                    mappingName = mappingName,
                    modifiers = modifiers.toMutableSet().apply { remove(JPModifier.FINAL) },
                    isFinal = JPModifier.FINAL in modifiers,
                    mixinAnnotations = mixinAnnotations.map(::lowerMixinAnnotation),
                )
            }

            is ShadowFunction -> with(source) {
                IrMixinShadowFunction(
                    sourceName = name,
                    sourceJvmName = jvmName,
                    name = jvmName.withUniqueModPrefix(),
                    parameters = parameters.map { it.asIrParameter() },
                    returnTypeName = returnTypeName,
                    mappingName = mappingName,
                    mixinAnnotations = mixinAnnotations.map(::lowerMixinAnnotation),
                    modifiers = if (JPModifier.STATIC in modifiers) modifiers else buildSet {
                        add(JPModifier.ABSTRACT)
                        addAll(modifiers.mapNotNull { modifier ->
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

    private fun lowerInjections(injection: PatchInjection): List<IrInjection> =
        when (injection) {
            is PatchNativeInjection -> {
                listOf(
                    IrNativeInjection(
                        jvmName = injection.jvmName,
                        extensionReceiverClassName = injection.extensionReceiverClassName,
                        mixinAnnotations = injection.mixinAnnotations.map(::lowerMixinAnnotation),
                        isStatic = injection.isStatic,
                        parameters = injection.parameters.map { parameter ->
                            IrNativeInjectionParameter(
                                parameter.name,
                                parameter.type.asIrTypeName(),
                                parameter.mixinAnnotations.map(::lowerMixinAnnotation),
                            )
                        },
                        returnTypeName = injection.returnType?.asIrTypeName(),
                    )
                )
            }
        }

    private fun resolveMixinClassName(sourceClassName: IrClassName, sourcePackageLCP: String?): IrClassName {
        val sourcePackageName = sourceClassName.packageName
        val mixinPackageName = buildString {
            append(kspArguments.mixinPackage)
            kspArguments.mixinGeneratedSubpackage?.let { append(".$it") }
            if (sourcePackageName != null && sourcePackageLCP != null && sourcePackageName != sourcePackageLCP) {
                append(".${sourcePackageName.removePrefix("$sourcePackageLCP.")}")
            }
        }
        return IrClassName.of(mixinPackageName, sourceClassName.simpleName).derived("Generated")
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
            is MixinAnnotationEnumArgumentValue -> IrMixinAnnotationEnumArgumentValue(entryClassName)
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

fun KClass<*>.asIrTypeName(): IrTypeName =
    asTypeName().asIrTypeName()

fun KPTypeName.asIrTypeName(): IrTypeName =
    IrTypeName(this)

fun KPClassName.asIrClassName(): IrClassName =
    IrClassName(this)

fun KPParameterizedTypeName.asIrParameterizedTypeName(): IrParameterizedTypeName =
    IrParameterizedTypeName(this)

fun KPWildcardTypeName.asIrWildcardTypeName(): IrWildcardTypeName =
    IrWildcardTypeName(this)

fun KPTypeVariableName.asIrTypeVariableName(): IrTypeVariableName =
    IrTypeVariableName(this)

fun KPFunctionalTypeName.asIrFunctionalTypeName(): IrLambdaTypeName =
    IrLambdaTypeName(this)

fun KSType.asIrTypeName(): IrTypeName =
    toTypeName().asIrTypeName()

fun KSClassDeclaration.asIrClassName(): IrClassName =
    toClassName().asIrClassName()
