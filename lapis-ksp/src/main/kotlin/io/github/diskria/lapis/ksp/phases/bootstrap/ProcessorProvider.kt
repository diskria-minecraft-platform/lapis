package io.github.diskria.lapis.ksp.phases.bootstrap

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.ksp.CoreProcessor
import io.github.diskria.lapis.ksp.Lapis
import io.github.diskria.lapis.ksp.extensions.elements
import io.github.diskria.lapis.ksp.extensions.quoted
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
        return CoreProcessor(
            parseOptions(environment.options, logger),
            environment.codeGenerator,
            logger,
        )
    }

    private fun parseOptions(options: Map<String, String>, logger: Logger): Options {
        val processorOptions = options
            .filterKeys { it.startsWith(ARGUMENT_PREFIX) }
            .mapKeys { it.key.removeArgumentPrefix() }

        val descriptorElements = serialDescriptor<Options>().elements
        val existingOptions = descriptorElements.map { it.name }.toSet()

        val unknownKeys = processorOptions.keys - existingOptions
        if (unknownKeys.isNotEmpty()) {
            logger.warn(
                buildString {
                    append("Unknown options: ${unknownKeys.joinToString { it.withArgumentPrefix() }}.")
                    appendLine()
                    append("Existing options: ${existingOptions.joinToString { it.withArgumentPrefix() }}.")
                }
            )
        }

        val requiredKeys = descriptorElements.filter { !it.isOptional }.map { it.name }.toSet()
        val missingKeys = requiredKeys - processorOptions.keys
        if (missingKeys.isNotEmpty()) {
            logger.fatal(
                buildString {
                    append("Missing options: ${missingKeys.joinToString { it.withArgumentPrefix() }}.")
                    appendLine()
                    append("Required options: ${requiredKeys.joinToString { it.withArgumentPrefix() }}.")
                }
            )
        }
        val jsonObject = buildJsonObject {
            processorOptions.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        val options = runCatching<Options> {
            optionsJson.decodeFromJsonElement(jsonObject)
        }.getOrElse { error ->
            logger.fatal("Failed to parse Lapis KSP arguments: ${error.message}")
        }
        if (!options.disableBuiltinsPackageIsolationWarning) {
            val uniqueModPrefix = options.uniqueModPrefix.lowercase().filter { it.isLetterOrDigit() }
            val builtinsPackage = options.builtinsPackage.lowercase().filter { it.isLetterOrDigit() }
            if (uniqueModPrefix !in builtinsPackage) {
                logger.warn(
                    "For better isolation between mods, " +
                        "it is recommended that 'builtinsPackage' (${options.builtinsPackage}) " +
                        "contains the 'uniqueModPrefix' ('${options.uniqueModPrefix}') " +
                        "to prevent class package collisions with other mods. " +
                        "To disable this warning, pass 'disableBuiltinsPackageIsolationWarning = true'."
                )
            }
        }
        return options
    }

    private fun String.withArgumentPrefix(): String =
        (ARGUMENT_PREFIX + this).quoted()

    private fun String.removeArgumentPrefix(): String =
        removePrefix(ARGUMENT_PREFIX)

    companion object {
        private val ARGUMENT_PREFIX: String = Lapis.NAME.lowercase() + "."
    }
}

private val optionsJson: Json = Json { ignoreUnknownKeys = true }
