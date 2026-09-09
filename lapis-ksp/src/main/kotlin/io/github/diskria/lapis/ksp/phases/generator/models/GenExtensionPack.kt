package io.github.diskria.lapis.ksp.phases.generator.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.models.IrKotlinFileBlueprint

class GenExtensionPack(
    override val originatingFiles: List<KSFile>,
    packageName: String?,
    fileName: String,
) : IrKotlinFileBlueprint(packageName, fileName)
