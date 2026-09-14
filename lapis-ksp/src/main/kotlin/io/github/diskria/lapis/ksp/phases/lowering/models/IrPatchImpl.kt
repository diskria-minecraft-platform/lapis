package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

class IrPatchImpl(
    val originatingFiles: List<KSFile>,
    val className: XClassName,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) {
    sealed interface ConstructorParameter {
        class Instance(val className: XClassName) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
