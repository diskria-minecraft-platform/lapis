package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.poetesse.interop.XClassName

class IrPatchImpl(
    val sourceFile: KSFile?,
    val className: XClassName,
    val constructorParameters: List<ConstructorParameter>,
    val initStrategy: InitStrategy,
) {
    sealed interface ConstructorParameter {
        class Instance(val targetType: IrTargetType) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
