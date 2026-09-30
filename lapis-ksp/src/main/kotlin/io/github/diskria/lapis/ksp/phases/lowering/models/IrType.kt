package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

class IrType(
    val inKotlin: XTypeName,
    val inJava: XTypeName = inKotlin,
    val returnContext: IrReturnContext?,
    val usedTypeParameterNames: Set<String>,
)

class IrReturnContext(
    val needsKotlinForwardCast: Boolean,
)
