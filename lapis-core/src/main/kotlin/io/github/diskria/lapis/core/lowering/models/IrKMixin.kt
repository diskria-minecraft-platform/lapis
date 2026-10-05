package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

sealed interface IrKMixin<O> {
    val className: XClassName
    val mixin: IrMixin<O>
}

class IrKMixinClass<O>(
    override val className: XClassName,
    override val mixin: IrMixin<O>,
    val impl: IrKMixinImpl<O>?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : IrKMixin<O> {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val type: IrType) : ConstructorParameter
    }
}

class IrKMixinInterface<O>(
    override val className: XClassName,
    override val mixin: IrMixin<O>,
) : IrKMixin<O>
