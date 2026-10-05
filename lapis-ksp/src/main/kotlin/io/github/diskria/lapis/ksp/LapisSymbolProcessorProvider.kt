package io.github.diskria.lapis.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.core.CoreOptions

@AutoService(SymbolProcessorProvider::class)
class LapisSymbolProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val logger = KspLogger(environment.logger)
        val options = CoreOptions.fromArguments(
            map = environment.options,
            warn = { logger.warn(it) },
            error = { logger.fatal(it) },
        )
        return LapisSymbolProcessor(options, environment.codeGenerator, logger).apply {
            environment.registerProcessorForNewFeatures(this)
        }
    }
}
