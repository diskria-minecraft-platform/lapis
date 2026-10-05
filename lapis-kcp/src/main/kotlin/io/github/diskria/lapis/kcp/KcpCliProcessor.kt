package io.github.diskria.lapis.kcp

import com.google.auto.service.AutoService
import io.github.diskria.lapis.core.CoreOptions
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
class KcpCliProcessor : CommandLineProcessor {

    override val pluginId: String = "io.github.diskria.lapis.kcp"

    override val pluginOptions: List<CliOption> =
        CoreOptions.specs.map { spec ->
            CliOption(
                optionName = spec.name,
                valueDescription = "<value>",
                description = spec.description,
                required = spec.isRequired,
            )
        }

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        val currentMap = configuration[KcpKeys.RAW_ARGUMENTS_KEY].orEmpty()
        configuration.put(KcpKeys.RAW_ARGUMENTS_KEY, currentMap + (option.optionName to value))
    }
}
