package io.github.diskria.lapis.phases.generator.models

import io.github.diskria.lapis.phases.generator.builders.GenKotlinEntity
import io.github.diskria.lapis.phases.lowering.models.IrParameter

class GenDescriptorWrapperImplResult(
    val constructorParameters: List<IrParameter>,
    val extensionPackEntities: List<GenKotlinEntity>,
)
