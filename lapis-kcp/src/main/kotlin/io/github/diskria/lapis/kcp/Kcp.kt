package io.github.diskria.lapis.kcp

import com.google.auto.service.AutoService
import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.kcp.fir.FirPlugin
import io.github.diskria.lapis.kcp.ir.IrPlugin
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.compiler.plugin.registerExtension
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class Kcp : CompilerPluginRegistrar() {

    override val pluginId: String = "io.github.diskria.lapis.kcp"
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val reporter = configuration[CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE]
        val options = CoreOptions.fromArguments(
            rawArguments = configuration.getMap(KcpKeys.RAW_ARGUMENTS),
            onWarn = { message -> reporter.report(CompilerMessageSeverity.WARNING, message) },
            onError = { message ->
                reporter.report(CompilerMessageSeverity.ERROR, message)
                error(message)
            },
        )
        FirExtensionRegistrar.registerExtension(FirPlugin(options))
        IrGenerationExtension.registerExtension(IrPlugin(options))
    }
}
