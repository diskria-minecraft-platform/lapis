package io.github.diskria.lapis

import io.github.diskria.lapis.extensions.doubleQuoted
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

abstract class KspArgumentProvider @Inject constructor(
    @Input
    val uniqueModPrefix: Property<String>,

    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    val mixinConfig: Provider<RegularFile>,

    @Optional
    @Input
    val mixinGeneratedSubpackage: Property<String>,

    @Optional
    @Input
    val disableLCP: Property<Boolean>,

    @Internal
    val layout: ProjectLayout,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = requireNotNull(uniqueModPrefix.orNull) {
            "Property 'lapis.uniqueModPrefix' is required but not set."
        }
        val mixinConfigFile = requireNotNull(mixinConfig.orNull?.asFile) {
            "Property 'lapis.mixinConfig' is required but not set."
        }
        require(mixinConfigFile.isFile) {
            "Mixin config file (${mixinConfigFile.relativePath()}) does not exist."
        }
        val jsonObject = runCatching {
            Json.parseToJsonElement(mixinConfigFile.readText()).jsonObject
        }.getOrElse { error ->
            error(
                "Cannot parse mixin config (${mixinConfigFile.relativePath()}):" +
                    (error.message?.let { "\n$it" } ?: " ensure the file contains valid JSON.")
            )
        }
        val rawMixinPackage = requireNotNull(jsonObject["package"]?.jsonPrimitive?.contentOrNull) {
            "Missing 'package' field in mixin config (${mixinConfigFile.relativePath()})."
        }
        val normalizedMixinPackage = rawMixinPackage.removeSuffix(".")
        require(normalizedMixinPackage.isValidArgumentValue()) {
            "Invalid 'package' field in mixin config (${mixinConfigFile.relativePath()}): " +
                "package cannot be default or contain whitespace characters, " +
                "but got: ${rawMixinPackage.doubleQuoted()}."
        }
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            "mixinPackage" to normalizedMixinPackage,
            mixinGeneratedSubpackage.orNull?.let { "mixinGeneratedSubpackage" to it },
            disableLCP.orNull?.let { "disableLCP" to it },
        ).map { (key, value) ->
            val prefixedKey = "lapis.$key"
            val valueStr = value.toString()
            require(valueStr.isValidArgumentValue()) {
                "Property '$prefixedKey' cannot be empty or contain whitespace characters, " +
                    "but got: ${valueStr.doubleQuoted()}."
            }
            "$prefixedKey=$valueStr"
        }
    }

    private fun File.relativePath() = getRootRelativePath(layout)
}

private fun String.isValidArgumentValue() = isNotEmpty() && none { it.isWhitespace() }
