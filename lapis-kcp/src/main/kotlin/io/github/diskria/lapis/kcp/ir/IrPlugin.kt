package io.github.diskria.lapis.kcp.ir

import io.github.diskria.lapis.core.cli.CliOptions
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

class IrPlugin(private val options: CliOptions) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {

    }
}
