package io.github.diskria.lapis.ksp.phases.generator.builders

import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.models.format
import io.github.diskria.poetesse.java.JPField
import io.github.diskria.poetesse.java.JPMethod

sealed interface GenJavaEntity {
    val callFormat: String
    val referenceFormat: String get() = "%N"
}

class GenJavaFieldEntity(val field: JPField) : GenJavaEntity {
    override val callFormat: String = referenceFormat
}

class GenJavaMethodEntity(
    val method: JPMethod,
    val parameters: List<IrParameter> = emptyList()
) : GenJavaEntity {
    override val callFormat: String = "$referenceFormat(${parameters.format})"
}
