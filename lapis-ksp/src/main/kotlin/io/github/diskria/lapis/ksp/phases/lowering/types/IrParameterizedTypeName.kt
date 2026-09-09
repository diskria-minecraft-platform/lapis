package io.github.diskria.lapis.ksp.phases.lowering.types

import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.poetesse.java.JPParameterizedTypeName
import io.github.diskria.poetesse.kotlin.KPParameterizedTypeName

class IrParameterizedTypeName(override val kotlin: KPParameterizedTypeName) : IrTypeName(kotlin) {

    override val java: JPParameterizedTypeName by lazy {
        JPParameterizedTypeName.get(
            kotlin.rawType.asIrClassName().java,
            *kotlin.typeArguments.map { it.asIrTypeName().box().java }.toTypedArray(),
        )
    }
}
