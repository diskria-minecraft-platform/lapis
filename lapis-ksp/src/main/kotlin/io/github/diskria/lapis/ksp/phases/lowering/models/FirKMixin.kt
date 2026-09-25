package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName

sealed interface FirKMixin {
    val className: XClassName
    val typeVariables: List<XTypeVariableName>
    val mixin: IrMixin
}

class FirKMixinClass(
    override val className: XClassName,
    override val typeVariables: List<XTypeVariableName>,
    override val mixin: IrMixin,
    val impl: IrKMixinImpl?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : FirKMixin {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val targetTypeCast: IrTargetSubtypeCast) : ConstructorParameter
    }
}

class FirKMixinInterface(
    override val className: XClassName,
    override val typeVariables: List<XTypeVariableName>,
    override val mixin: IrMixin,
) : FirKMixin
