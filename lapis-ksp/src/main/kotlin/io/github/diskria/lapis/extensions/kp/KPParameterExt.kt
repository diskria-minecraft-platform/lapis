package io.github.diskria.lapis.extensions.kp

import io.github.diskria.lapis.extensions.common.Builder
import io.github.diskria.lapis.phases.generator.builders.IrKotlinCodeBlock
import io.github.diskria.poetesse.kotlin.KPParameter
import io.github.diskria.poetesse.kotlin.KPParameterBuilder

fun KPParameterBuilder.setDefaultValue(
    format: String,
    argumentsBuilder: Builder<IrKotlinCodeBlock.Arguments>
) {
    defaultValue(buildKotlinCodeBlock(format, argumentsBuilder))
}

val List<KPParameter>.format: String
    get() = joinToString { "%N" }
