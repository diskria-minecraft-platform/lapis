package io.github.diskria.lapis.ksp.phases.lowering.models

class IrTargetSubtypeCast(
    val type: IrType,
    val isUnsafeCastRequired: Boolean,
    val isTargetCastRequired: Boolean,
)
