package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.poetesse.interop.XClassName

class IrPatchImpl(
    val patchOriginatingFile: KSFile?,
    val className: XClassName,
    val constructorParameters: List<ConstructorParameter>,
) {
    sealed interface ConstructorParameter {
        class Instance(val name: String, val type: IrTargetType) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
