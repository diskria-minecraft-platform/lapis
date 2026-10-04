package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

class IrType(
    val kotlin: IrKotlinType,
    val java: IrJavaType,
)

class IrKotlinType(
    val type: XTypeName,
    val javaCastType: XTypeName? = null,
    val isReturnable: Boolean = false,
    val isFunctionalType: Boolean = false,
)

class IrJavaType(
    val type: XTypeName,
    val kotlinCastType: XTypeName? = null,
    val isReturnable: Boolean = false,
)
