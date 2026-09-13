package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

class IrResult(
    val patches: List<IrPatch>,
)

sealed class IrSourceFile(val className: XClassName)

class IrPatch(
    className: XClassName,
    val constructorArguments: List<IrPatchConstructorArgument>,
    val impl: IrPatchImpl?,
    val mixin: IrMixin,
) : IrSourceFile(className)

class IrMixin(
    val originatingFiles: List<KSFile>,
    val className: XClassName,
    val env: Env,
    val injections: List<IrInjection>,
    val duck: IrMixinDuck?,
    val targetClassName: XClassName?,
    val annotations: List<IrMixinAnnotation>,
)

sealed interface IrPatchConstructorArgument
class IrPatchConstructorOriginArgument(val className: XClassName) : IrPatchConstructorArgument

class IrPatchImpl(
    val originatingFiles: List<KSFile>,
    val className: XClassName,
    val constructorParameters: List<IrPatchImplConstructorParameter>,
    val initStrategy: InitStrategy,
)

sealed interface IrPatchImplConstructorParameter
class IrPatchImplConstructorInstanceParameter(val className: XClassName) : IrPatchImplConstructorParameter
class IrPatchImplConstructorDuckParameter(val className: XClassName) : IrPatchImplConstructorParameter
