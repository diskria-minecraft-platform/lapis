package io.github.diskria.lapis

import io.github.diskria.lapis.extensions.getRootRelativePathString
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
import javax.inject.Inject

abstract class LapisKspArgumentProvider @Inject constructor(
    @Input
    val uniqueModPrefix: Property<String>,

    @Optional
    @Input
    val enableFabricTweaks: Property<Boolean>,

    @Optional
    @Input
    val enableForgeTweaks: Property<Boolean>,

    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFile
    val mixinConfigFile: Provider<RegularFile>,

    @Optional
    @Input
    val builtinsPackage: Property<String>,

    @Internal
    val layout: ProjectLayout,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = uniqueModPrefix.orNull ?: error("Property 'uniqueModPrefix' is required but not set.")
        val configFile = mixinConfigFile.get().asFile
        val mixinPackage = runCatching {
            Json.parseToJsonElement(configFile.readText()).jsonObject["package"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: run {
            val configPath = configFile.getRootRelativePathString(layout)
            error(
                "Invalid or unparseable JSON in mixin config '${configFile.name}' ($configPath). " +
                    "Ensure the file is valid JSON and contains a 'package' field."
            )
        }
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            enableFabricTweaks.orNull?.let { "enableFabricTweaks" to it },
            enableForgeTweaks.orNull?.let { "enableForgeTweaks" to it },
            "mixinPackage" to mixinPackage,
            builtinsPackage.orNull?.let { "builtinsPackage" to it },
        ).map { (key, value) -> "lapis.$key=$value" }
    }
}
