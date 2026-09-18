package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

sealed interface IrPatch {
    val className: XClassName
    val mixin: IrMixin
}

class IrPatchClass(
    override val className: XClassName,
    override val mixin: IrMixin,
    val impl: IrPatchImpl?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : IrPatch {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val type: IrTargetCompatType) : ConstructorParameter
    }
}

class IrPatchInterface(
    override val className: XClassName,
    override val mixin: IrMixin,
) : IrPatch
