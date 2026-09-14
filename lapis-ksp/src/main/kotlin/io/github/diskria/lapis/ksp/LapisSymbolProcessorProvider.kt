package io.github.diskria.lapis.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.ksp.extensions.elements
import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.lapis.ksp.logging.KspOptions
import io.github.diskria.lapis.ksp.logging.Logger
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@AutoService(SymbolProcessorProvider::class)
class ProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val logger = Logger(environment.logger)
        val kspOptions = parseOptions(environment.options, logger)
        return LapisSymbolProcessor(kspOptions, environment.codeGenerator, logger)
    }

    private fun parseOptions(options: Map<String, String>, logger: Logger): KspOptions {
        val scopedOptions = options.filterKeys { it.startsWith(ARGUMENT_PREFIX) }
        val descriptorElements = serialDescriptor<KspOptions>().elements
        val existingKeys = descriptorElements.map { it.name.withArgumentPrefix() }.toSet()
        val unknownKeys = scopedOptions.keys - existingKeys
        if (unknownKeys.isNotEmpty()) {
            logger.warn("Unknown arguments: ${unknownKeys.joinToString { it.quoted() }}.")
        }
        val requiredKeys = descriptorElements.filter { !it.isOptional }.map { it.name.withArgumentPrefix() }.toSet()
        val missingRequiredKeys = requiredKeys - scopedOptions.keys
        if (missingRequiredKeys.isNotEmpty()) {
            logger.fatal("Missing required arguments: ${missingRequiredKeys.joinToString { it.quoted() }}.")
        }
        return runCatching<KspOptions> {
            optionsJson.decodeFromJsonElement(buildJsonObject {
                scopedOptions.forEach { (rawKey, value) ->
                    put(rawKey.removeArgumentPrefix(), JsonPrimitive(value))
                }
            })
        }.getOrElse { error ->
            logger.fatal("Failed to parse KSP arguments: ${error.message}")
        }
    }

    private fun String.withArgumentPrefix(): String =
        ARGUMENT_PREFIX + this

    private fun String.removeArgumentPrefix(): String =
        removePrefix(ARGUMENT_PREFIX)

    companion object {
        private const val ARGUMENT_PREFIX: String = "lapis."
    }
}

private val optionsJson: Json = Json { ignoreUnknownKeys = true }
