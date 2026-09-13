package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

open class IrParameter(
    val name: String,
    val type: XTypeName,
)

class IrSetterParameter(type: XTypeName) : IrParameter("newValue", type)

val List<IrParameter>.format: String
    get() = joinToString { "%N" }
