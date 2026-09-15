package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.models.IrFunctionParameter

open class FunctionParameter(val name: String, private val type: KSType) {
    fun asIrFunctionParameter(): IrFunctionParameter = IrFunctionParameter(name, type.toXTypeName())
}
