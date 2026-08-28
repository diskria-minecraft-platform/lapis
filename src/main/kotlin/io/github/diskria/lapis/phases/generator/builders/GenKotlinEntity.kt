package io.github.diskria.lapis.phases.generator.builders

import io.github.diskria.lapis.phases.lowering.models.IrParameter
import io.github.diskria.lapis.phases.lowering.models.format
import io.github.diskria.poetesse.kotlin.KPFunction
import io.github.diskria.poetesse.kotlin.KPProperty

sealed interface GenKotlinEntity {
    val callFormat: String
    val referenceFormat: String get() = "%N"
}

class GenKotlinPropertyEntity(val property: KPProperty) : GenKotlinEntity {
    override val callFormat: String = referenceFormat
}

class GenKotlinFunctionEntity(
    val function: KPFunction,
    val parameters: List<IrParameter> = emptyList()
) : GenKotlinEntity {
    override val callFormat: String = "$referenceFormat(${parameters.format})"
}
