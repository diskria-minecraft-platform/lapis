package io.github.diskria.lapis.kcp

import com.google.auto.service.AutoService
import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.kcp.fir.FirPlugin
import io.github.diskria.lapis.kcp.ir.IrPlugin
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.registerExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class Kcp : CompilerPluginRegistrar() {

    override val pluginId: String = "io.github.diskria.lapis.kcp"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val logger = KcpLogger(configuration.messageCollector)
        val options = CoreOptions.fromArguments(
            rawArguments = configuration.getMap(KcpKeys.RAW_ARGUMENTS),
            onWarn = { message -> logger.warn(message) },
            onError = { message -> logger.fatal(message) },
        )
        FirExtensionRegistrar.registerExtension(FirPlugin(logger, options))
        IrGenerationExtension.registerExtension(IrPlugin(options))
    }
}
