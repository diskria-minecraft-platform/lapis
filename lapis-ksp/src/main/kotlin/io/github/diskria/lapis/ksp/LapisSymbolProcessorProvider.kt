package io.github.diskria.lapis.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.diskria.lapis.ksp.extensions.elements
import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.lapis.ksp.logging.KspArguments
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
        return LapisSymbolProcessor(
            parseArguments(environment.options, logger),
            environment.codeGenerator,
            logger,
        )
    }

    private fun parseArguments(map: Map<String, String>, logger: Logger): KspArguments {
        val arguments = map.filterKeys { it.startsWith(ARGUMENT_PREFIX) }.mapKeys { it.key.removeArgumentPrefix() }
        val descriptorElements = serialDescriptor<KspArguments>().elements
        val existingArguments = descriptorElements.map { it.name }.toSet()
        val unknownKeys = arguments.keys - existingArguments
        if (unknownKeys.isNotEmpty()) {
            logger.warn(
                buildString {
                    append("Unknown arguments: ${unknownKeys.joinToString { it.withArgumentPrefix() }}.")
                    appendLine()
                    append("Existing arguments: ${existingArguments.joinToString { it.withArgumentPrefix() }}.")
                }
            )
        }
        val requiredKeys = descriptorElements.filter { !it.isOptional }.map { it.name }.toSet()
        val missingKeys = requiredKeys - arguments.keys
        if (missingKeys.isNotEmpty()) {
            logger.fatal(
                buildString {
                    append("Missing arguments: ${missingKeys.joinToString { it.withArgumentPrefix() }}.")
                    appendLine()
                    append("Required arguments: ${requiredKeys.joinToString { it.withArgumentPrefix() }}.")
                }
            )
        }
        val jsonObject = buildJsonObject {
            arguments.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        val kspArguments = runCatching<KspArguments> {
            argumentsJson.decodeFromJsonElement(jsonObject)
        }.getOrElse { error ->
            logger.fatal("Failed to parse Lapis KSP arguments: ${error.message}")
        }
        if (!kspArguments.disableBuiltinsPackageIsolationWarning) {
            val uniqueModPrefix = kspArguments.uniqueModPrefix.lowercase().filter { it.isLetterOrDigit() }
            val builtinsPackage = kspArguments.builtinsPackage.lowercase().filter { it.isLetterOrDigit() }
            if (uniqueModPrefix !in builtinsPackage) {
                logger.warn(
                    "For better isolation between mods, " +
                        "it is recommended that 'builtinsPackage' (${kspArguments.builtinsPackage}) " +
                        "contains the 'uniqueModPrefix' ('${kspArguments.uniqueModPrefix}') " +
                        "to prevent class package collisions with other mods. " +
                        "To disable this warning, pass 'disableBuiltinsPackageIsolationWarning = true'."
                )
            }
        }
        return kspArguments
    }

    private fun String.withArgumentPrefix(): String =
        (ARGUMENT_PREFIX + this).quoted()

    private fun String.removeArgumentPrefix(): String =
        removePrefix(ARGUMENT_PREFIX)

    companion object {
        private const val ARGUMENT_PREFIX: String = "lapis."
    }
}

private val argumentsJson: Json = Json { ignoreUnknownKeys = true }
