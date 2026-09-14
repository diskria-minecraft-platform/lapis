package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XClassName

class IrPatch(
    val className: XClassName,
    val constructorArguments: List<ConstructorArgument>,
    val impl: IrPatchImpl?,
    val mixin: IrMixin,
) {
    sealed interface ConstructorArgument {
        class Origin(val className: XClassName) : ConstructorArgument
    }
}
