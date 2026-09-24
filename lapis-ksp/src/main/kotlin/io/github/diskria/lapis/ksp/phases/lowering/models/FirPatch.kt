package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName

sealed interface FirPatch {
    val className: XClassName
    val typeVariables: List<XTypeVariableName>
    val mixin: IrMixin
}

class FirPatchClass(
    override val className: XClassName,
    override val typeVariables: List<XTypeVariableName>,
    override val mixin: IrMixin,
    val impl: IrPatchImpl?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : FirPatch {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val targetTypeCast: IrTargetSubtypeCast) : ConstructorParameter
    }
}

class FirPatchInterface(
    override val className: XClassName,
    override val typeVariables: List<XTypeVariableName>,
    override val mixin: IrMixin,
) : FirPatch
