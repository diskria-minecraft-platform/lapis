package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName

interface IrReturnable {
    val returnTypeName: IrTypeName?
    val isReturn: Boolean get() = returnTypeName != null
}
