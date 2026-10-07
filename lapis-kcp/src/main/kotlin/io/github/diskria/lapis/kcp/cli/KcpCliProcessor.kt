package io.github.diskria.lapis.kcp.cli

import com.google.auto.service.AutoService
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.kcp.logging.KcpLogger
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
class KcpCliProcessor : CommandLineProcessor {

    override val pluginId: String = "io.github.diskria.lapis.kcp"

    override val pluginOptions: List<CliOption> =
        CliOptions.specs.map { spec ->
            CliOption(
                optionName = spec.name,
                valueDescription = "<value>",
                description = spec.description,
                required = spec.isRequired,
            )
        }

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        configuration.appendMap(CLI_ARGUMENTS, option.optionName, value)
    }

    companion object {
        private val CLI_ARGUMENTS = CompilerConfigurationKey.create<Map<String, String>>("lapis.cliArguments")

        fun getOptions(configuration: CompilerConfiguration, logger: KcpLogger) = CliOptions.fromArguments(
            rawArguments = configuration.getMap(CLI_ARGUMENTS),
            onWarn = { message -> logger.warn(message) },
            onError = { message -> logger.fatal(message) },
        )
    }
}

private fun <K, V> CompilerConfiguration.appendMap(option: CompilerConfigurationKey<Map<K, V>>, key: K, value: V) {
    val map = getMap(option).toMutableMap()
    map[key] = value
    put(option, map)
}
