package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.poetesse.interop.XClassName

class IrKMixinImpl(
    val originatingFile: KSFile?,
    val className: XClassName,
    val typeVariables: IrTypeVariables,
    val constructorParameters: List<ConstructorParameter>,
) {
    sealed interface ConstructorParameter {
        class Instance(val name: String, val type: IrType) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
