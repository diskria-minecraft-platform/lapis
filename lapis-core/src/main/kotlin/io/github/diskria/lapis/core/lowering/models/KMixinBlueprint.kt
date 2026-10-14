package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

sealed interface KMixinBlueprint<O> {
    val className: XClassName
    val mixin: MixinBlueprint<O>
}

class KMixinClassBlueprint<O>(
    override val className: XClassName,
    override val mixin: MixinBlueprint<O>,
    val impl: KMixinImplBlueprint<O>?,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) : KMixinBlueprint<O> {
    sealed interface ConstructorParameter {
        class Origin(val name: String, val type: TypeBlueprint) : ConstructorParameter
    }
}

class KMixinInterfaceBlueprint<O>(
    override val className: XClassName,
    override val mixin: MixinBlueprint<O>,
) : KMixinBlueprint<O>
