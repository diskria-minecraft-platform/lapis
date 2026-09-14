package io.github.diskria.lapis

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class MergeMixinConfigsTask : DefaultTask() {

    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    abstract val userConfig: RegularFileProperty

    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFiles
    abstract val generatedConfig: RegularFileProperty

    @get:OutputFile
    abstract val mergedConfig: RegularFileProperty

    @TaskAction
    fun merge() {
        val userConfigFile = userConfig.get().asFile
        val genConfigFile = generatedConfig.orNull?.asFile
        val mergedConfigFile = mergedConfig.get().asFile
        if (genConfigFile == null || !genConfigFile.exists()) {
            userConfigFile.copyTo(mergedConfigFile, overwrite = true)
            return
        }
        val genFileText = genConfigFile.readText()
        val genJson = Json.parseToJsonElement(genFileText).jsonObject
        if (genJson.isEmpty()) {
            userConfigFile.copyTo(mergedConfigFile, overwrite = true)
            return
        }
        val userFileText = userConfigFile.readText()
        val indent = userFileText.lineSequence()
            .map { line -> line.takeWhile { it.isWhitespace() } }
            .firstOrNull { it.isNotEmpty() }
            ?: "  "
        val trailingWhitespaces = userFileText.takeLastWhile { it.isWhitespace() }
        val userJson = Json.parseToJsonElement(userFileText).jsonObject
        val mergedJson = mergeJsonObjects(userJson, genJson)
        val jsonForOutput = Json {
            prettyPrint = true
            prettyPrintIndent = indent
        }
        val mergedFileText = jsonForOutput.encodeToString(JsonElement.serializer(), mergedJson)
        mergedConfigFile.parentFile.mkdirs()
        mergedConfigFile.writeText(mergedFileText + trailingWhitespaces)
    }

    private fun mergeJsonObjects(userJson: JsonObject, genJson: JsonObject): JsonObject = buildJsonObject {
        val processedGenKeys = mutableSetOf<String>()
        userJson.forEach { (key, userValue) ->
            val genValue = genJson[key]
            val mergedValue = if (genValue != null) {
                processedGenKeys.add(key)
                when {
                    userValue is JsonArray && genValue is JsonArray -> JsonArray((userValue + genValue).distinct())
                    else -> userValue
                }
            } else {
                userValue
            }
            put(key, mergedValue)
        }
        genJson.entries
            .filterNot { it.key in processedGenKeys }
            .forEach { (key, genValue) -> put(key, genValue) }
    }
}
