package io.github.diskria.lapis.ksp.phases.generator.models

import io.github.diskria.lapis.ksp.phases.generator.builders.GenKotlinEntity
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter

class GenDescriptorWrapperImplResult(
    val constructorParameters: List<IrParameter>,
    val extensionPackEntities: List<GenKotlinEntity>,
)
