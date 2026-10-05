package io.github.diskria.lapis.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.core.CoreOptions

@AutoService(SymbolProcessorProvider::class)
class KspProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val logger = KspLogger(environment.logger)
        val options = CoreOptions.fromArguments(
            rawArguments = environment.options,
            onWarn = { message -> logger.warn(message) },
            onError = { message -> logger.fatal(message) },
        )
        return Ksp(options, environment.codeGenerator, logger).apply {
            environment.registerProcessorForNewFeatures(this)
        }
    }
}
