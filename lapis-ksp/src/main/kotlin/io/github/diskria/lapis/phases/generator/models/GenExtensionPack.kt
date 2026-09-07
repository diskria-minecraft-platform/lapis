package io.github.diskria.lapis.phases.generator.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.phases.lowering.models.IrKotlinFileBlueprint

class GenExtensionPack(
    override val originatingFiles: List<KSFile>,
    packageName: String?,
    fileName: String,
) : IrKotlinFileBlueprint(packageName, fileName)
