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
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    val mixinConfig: Provider<RegularFile>,

    @Optional
    @Input
    val builtinsPackage: Property<String>,

    @Optional
    @Input
    val enableFabricTweaks: Property<Boolean>,

    @Optional
    @Input
    val enableForgeTweaks: Property<Boolean>,

    @Optional
    @Input
    val disableLCP: Property<Boolean>,

    @Optional
    @Input
    val disableBuiltinsPackageIsolationWarning: Property<Boolean>,

    @Internal
    val layout: ProjectLayout,
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        val uniqueModPrefix = uniqueModPrefix.orNull
            ?: error("Property 'lapis.uniqueModPrefix' is required but not set.")
        val mixinConfigFile = mixinConfig.orNull?.asFile
            ?: error("Property 'lapis.mixinConfig' is required but not set.")
        require(mixinConfigFile.isFile) {
            val configPath = mixinConfigFile.getRootRelativePathString(layout)
            "Mixin config ($configPath) does not exist."
        }
        val mixinPackage = runCatching {
            Json.parseToJsonElement(mixinConfigFile.readText()).jsonObject["package"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: run {
            val configPath = mixinConfigFile.getRootRelativePathString(layout)
            error(
                "Invalid or unparseable mixin config ($configPath). " +
                    "Ensure the file is valid JSON and contains a 'package' field."
            )
        }
        return listOfNotNull(
            "uniqueModPrefix" to uniqueModPrefix,
            "mixinPackage" to mixinPackage,
            builtinsPackage.orNull?.let { "builtinsPackage" to it },
            enableFabricTweaks.orNull?.let { "enableFabricTweaks" to it },
            enableForgeTweaks.orNull?.let { "enableForgeTweaks" to it },
            disableLCP.orNull?.let { "disableLCP" to it },
            disableBuiltinsPackageIsolationWarning.orNull?.let { "disableBuiltinsPackageIsolationWarning" to it },
        ).map { (key, value) -> "lapis.$key=$value" }
    }
}
