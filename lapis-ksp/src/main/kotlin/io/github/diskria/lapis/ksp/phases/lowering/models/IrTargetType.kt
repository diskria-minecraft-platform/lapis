package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

class IrTargetType(
    val typeName: XTypeName,
    val isObjectCastRequired: Boolean,
    val isTargetTypeCastRequired: Boolean,
)
