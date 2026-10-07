package io.github.diskria.lapis.ksp.cli

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.ksp.Ksp
import io.github.diskria.lapis.ksp.logging.KspLogger

@AutoService(SymbolProcessorProvider::class)
class KspCliProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val logger = KspLogger(environment.logger)
        val options = CliOptions.fromArguments(
            rawArguments = environment.options,
            onWarn = { message -> logger.warn(message) },
            onError = { message -> logger.fatal(message) },
        )
        return Ksp(options, environment.codeGenerator, logger).apply {
            environment.registerProcessorForNewFeatures(this)
        }
    }
}
