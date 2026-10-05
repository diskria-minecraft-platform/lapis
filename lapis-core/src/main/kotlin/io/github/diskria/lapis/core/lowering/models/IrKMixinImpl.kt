package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName

class IrKMixinImpl<O>(
    val origin: O?,
    val className: XClassName,
    val typeVariables: List<XTypeVariableName>,
    val constructorParameters: List<ConstructorParameter>,
) {
    sealed interface ConstructorParameter {
        class Instance(val name: String, val type: IrType) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
