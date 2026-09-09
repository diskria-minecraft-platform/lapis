package io.github.diskria.lapis.ksp.phases.lowering.types

import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeVariableName
import io.github.diskria.poetesse.java.JPTypeVariableName
import io.github.diskria.poetesse.kotlin.KPTypeVariableName

class IrTypeVariableName(override val kotlin: KPTypeVariableName) : IrTypeName(kotlin) {

    override val java: JPTypeVariableName by lazy {
        JPTypeVariableName.get(kotlin.name, *kotlin.bounds.map { it.asIrTypeName().java }.toTypedArray())
    }

    companion object {
        fun of(name: String, vararg bounds: IrTypeName): IrTypeVariableName =
            KPTypeVariableName(name, bounds.map { it.kotlin }).asIrTypeVariableName()
    }
}
