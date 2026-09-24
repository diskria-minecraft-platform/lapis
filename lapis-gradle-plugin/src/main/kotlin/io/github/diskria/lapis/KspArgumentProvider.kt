package io.github.diskria.lapis

import io.github.diskria.lapis.extensions.doubleQuoted
import io.github.diskria.lapis.extensions.getRootRelativePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.CommandLineArgumentProvider
import javax.inject.Inject

abstract class KspArgumentProvider @Inject constructor(layout: ProjectLayout) : CommandLineArgumentProvider {

    @get:Optional
    @get:Input
    abstract val uniqueModPrefix: Property<String>

    @get:Optional
    @get:Input
    abstract val mixinGeneratedSubpackage: Property<String>

    @get:Optional
    @get:Input
    abstract val disableLCP: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val nullableAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val nonNullAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val mixinAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val uniqueAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val shadowAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val mutableAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val finalAnnotation: Property<String>

    @get:Optional
    @get:Input
    abstract val mixinAnnotationPackages: ListProperty<String>

    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    abstract val mixinConfig: RegularFileProperty

    @get:Internal
    private val mixinConfigPath: Provider<String> = mixinConfig.map { it.asFile.getRootRelativePath(layout) }

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = requireNotNull(uniqueModPrefix.orNull) {
            "Property 'lapis.uniqueModPrefix' is required but not set."
        }
        val mixinConfigFile = requireNotNull(mixinConfig.orNull?.asFile) {
            "Property 'lapis.mixinConfig' is required but not set."
        }
        require(mixinConfigFile.isFile) {
            "Mixin config file (${mixinConfigPath.get()}) does not exist."
        }
        val jsonObject = runCatching {
            Json.parseToJsonElement(mixinConfigFile.readText()).jsonObject
        }.getOrElse { error ->
            error(
                "Failed to parse mixin config (${mixinConfigPath.get()}):" +
                    (error.message?.let { "\n$it" } ?: " ensure the file contains valid JSON.")
            )
        }
        val rawMixinPackage = requireNotNull(jsonObject["package"]?.jsonPrimitive?.contentOrNull) {
            "Missing 'package' field in mixin config (${mixinConfigPath.get()})."
        }
        val normalizedMixinPackage = rawMixinPackage.removeSuffix(".")
        require(normalizedMixinPackage.isValidArgumentValue()) {
            "Invalid 'package' field in mixin config (${mixinConfigPath.get()}): " +
                "package cannot be default or contain whitespace characters, " +
                "but got: ${rawMixinPackage.doubleQuoted()}."
        }
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            "mixinPackage" to normalizedMixinPackage,
            mixinGeneratedSubpackage.orNull?.let { "mixinGeneratedSubpackage" to it },
            disableLCP.orNull?.let { "disableLCP" to it },
            nullableAnnotation.orNull?.let { "nullableAnnotation" to it },
            nonNullAnnotation.orNull?.let { "nonNullAnnotation" to it },
            mixinAnnotation.orNull?.let { "mixinAnnotation" to it },
            uniqueAnnotation.orNull?.let { "uniqueAnnotation" to it },
            shadowAnnotation.orNull?.let { "shadowAnnotation" to it },
            mutableAnnotation.orNull?.let { "mutableAnnotation" to it },
            finalAnnotation.orNull?.let { "finalAnnotation" to it },
            mixinAnnotationPackages.orNull?.ifEmpty { null }?.let { "mixinAnnotationPackages" to it.joinToString(",") },
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
}

private fun String.isValidArgumentValue() = isNotEmpty() && none { it.isWhitespace() }
