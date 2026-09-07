package io.github.diskria.lapis.phases.generator.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.phases.lowering.models.IrResourceBlueprint

class GenTweakAccessorConfig(
    override val originatingFiles: List<KSFile>,
    fileName: String,
) : IrResourceBlueprint(fileName)
