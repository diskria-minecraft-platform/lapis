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
        if (!mixinConfigFile.isFile) {
            error("Mixin config file (${mixinConfigFile.relativePath()}) does not exist.")
        }
        val jsonObject = runCatching { Json.parseToJsonElement(mixinConfigFile.readText()).jsonObject }.getOrElse {
            error("Cannot parse mixin config (${mixinConfigFile.relativePath()}). Ensure the file contains valid JSON.")
        }
        val mixinPackage = jsonObject["package"]?.jsonPrimitive?.contentOrNull?.removeSuffix(".")
            ?: error("Missing 'package' field in mixin config (${mixinConfigFile.relativePath()}).")
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

    private fun File.relativePath() = getRootRelativePath(layout)
}
