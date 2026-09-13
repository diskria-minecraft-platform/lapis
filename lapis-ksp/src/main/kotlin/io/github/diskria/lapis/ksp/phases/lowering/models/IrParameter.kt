package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

open class IrParameter(
    val name: String,
    val typeName: XTypeName,
)

class IrSetterParameter(typeName: XTypeName) : IrParameter("newValue", typeName)
