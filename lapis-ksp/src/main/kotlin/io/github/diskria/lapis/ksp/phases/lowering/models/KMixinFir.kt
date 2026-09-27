package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

sealed interface KMixinFir {
    val className: XClassName
    val typeVariables: IrTypeVariables
    val mixin: IrMixin
}

class KMixinFirClass(
    override val className: XClassName,
    override val typeVariables: IrTypeVariables,
    override val mixin: IrMixin,
    val impl: IrKMixinImpl?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : KMixinFir {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val type: IrType) : ConstructorParameter
    }
}

class KMixinFirInterface(
    override val className: XClassName,
    override val typeVariables: IrTypeVariables,
    override val mixin: IrMixin,
) : KMixinFir
