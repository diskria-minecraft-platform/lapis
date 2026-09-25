package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName

class IrKMixinImpl(
    val originatingFile: KSFile?,
    val className: XClassName,
    val typeVariables: List<XTypeVariableName>,
    val constructorParameters: List<ConstructorParameter>,
) {
    sealed interface ConstructorParameter {
        class Instance(val name: String, val targetTypeCast: IrTargetSubtypeCast) : ConstructorParameter
        class Duck(val className: XClassName) : ConstructorParameter
    }
}
