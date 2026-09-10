package io.github.diskria.lapis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.CommandLineArgumentProvider

class LapisKspArgumentProvider(
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
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = uniqueModPrefix.orNull ?: error("Property 'uniqueModPrefix' is required but not set.")
        val configFile = mixinConfigFile.get().asFile
        val mixinPackage = Json.parseToJsonElement(configFile.readText()).jsonObject["package"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: error(
                "Missing required 'package' field in " +
                    "mixin config '${configFile.name}' (${configFile.absolutePath})."
            )
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            enableFabricTweaks.orNull?.let { "enableFabricTweaks" to it },
            enableForgeTweaks.orNull?.let { "enableForgeTweaks" to it },
            "mixinPackage" to mixinPackage,
            builtinsPackage.orNull?.let { "builtinsPackage" to it },
        ).map { (key, value) -> "lapis.$key=$value" }
    }
}
