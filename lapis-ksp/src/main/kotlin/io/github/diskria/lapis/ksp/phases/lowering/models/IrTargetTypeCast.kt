package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

class IrTargetTypeCast(
    val typeName: XTypeName,
    val isUnsafeCastRequired: Boolean,
    val isTargetCastRequired: Boolean,
)
