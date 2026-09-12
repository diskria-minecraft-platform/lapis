package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.poetesse.java.JPTypeKind
import io.github.diskria.poetesse.kotlin.KPTypeKind

class IrResult(
    val patches: List<IrPatch>,
)

sealed class IrSourceFile(val className: IrClassName)

abstract class IrMixinRelatedBlueprint(typeKind: JPTypeKind) : IrJavaFileBlueprint(typeKind) {
    abstract val side: Side
}

class IrPatch(
    className: IrClassName,
    val constructorArguments: List<IrPatchConstructorArgument>,
    val impl: IrPatchImpl?,
    val mixin: IrMixin,
) : IrSourceFile(className)

class IrMixin(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    override val side: Side,
    val injections: List<IrInjection>,
    val bridge: IrMixinBridge?,
    val targetClassName: IrClassName?,
    val mixinAnnotations: List<IrMixinAnnotation>,
) : IrMixinRelatedBlueprint(JPTypeKind.CLASS)

sealed interface IrPatchConstructorArgument
class IrPatchConstructorOriginArgument(val className: IrClassName) : IrPatchConstructorArgument

class IrPatchImpl(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    val constructorParameters: List<IrPatchImplConstructorParameter>,
    val initStrategy: InitStrategy,
) : IrKotlinClassBlueprint(KPTypeKind.CLASS)

sealed interface IrPatchImplConstructorParameter
class IrPatchImplConstructorInstanceParameter(val className: IrClassName) : IrPatchImplConstructorParameter
object IrPatchImplConstructorInternalBridgeParameter : IrPatchImplConstructorParameter
