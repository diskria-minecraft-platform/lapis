package io.github.diskria.lapis

import io.github.diskria.lapis.extensions.getRootRelativePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.CommandLineArgumentProvider
import java.io.File
import javax.inject.Inject

abstract class LapisKspArgumentProvider @Inject constructor(
    @Input
    val uniqueModPrefix: Property<String>,

    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    val mixinConfig: Provider<RegularFile>,

    @Optional
    @Input
    val disableLCP: Property<Boolean>,

    @Internal
    val layout: ProjectLayout,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = uniqueModPrefix.orNull
            ?: error("Property 'lapis.uniqueModPrefix' is required but not set.")
        val mixinConfigFile = mixinConfig.orNull?.asFile
            ?: error("Property 'lapis.mixinConfig' is required but not set.")
        if (!mixinConfigFile.isFile) {
            error("Mixin config file (${mixinConfigFile.relativePath()}) does not exist.")
        }
        val jsonObject = runCatching { Json.parseToJsonElement(mixinConfigFile.readText()).jsonObject }.getOrElse {
            error("Cannot parse mixin config (${mixinConfigFile.relativePath()}): ensure the file contains valid JSON.")
        }
        val rawMixinPackage = jsonObject["package"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing 'package' field in mixin config (${mixinConfigFile.relativePath()}).")
        val normalizedMixinPackage = rawMixinPackage.removeSuffix(".").takeIf { it.isNotBlank() }
            ?: error(
                "Invalid 'package' field in mixin config (${mixinConfigFile.relativePath()}): " +
                    "package cannot be empty or default."
            )
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            "mixinPackage" to normalizedMixinPackage,
            disableLCP.orNull?.let { "disableLCP" to it },
        ).map { (key, value) ->
            val valueStr = value.toString()
            if (valueStr.isBlank()) {
                error("Property 'lapis.$key' cannot be empty or blank.")
            }
            "lapis.$key=$valueStr"
        }
    }

    private fun File.relativePath() = getRootRelativePath(layout)
}
