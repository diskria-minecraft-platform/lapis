package io.github.diskria.lapis

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class MergeMixinConfigsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val userConfig: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val generatedConfig: RegularFileProperty

    @get:OutputFile
    abstract val mergedConfig: RegularFileProperty

    private val json = Json { prettyPrint = true }

    @TaskAction
    fun merge() {
        val userFile = userConfig.get().asFile
        val genFile = generatedConfig.orNull?.asFile
        val mergedFile = mergedConfig.get().asFile
        if (genFile == null || !genFile.exists()) {
            userFile.copyTo(mergedFile, overwrite = true)
            return
        }
        val genFileText = genFile.readText()
        val genJson = json.parseToJsonElement(genFileText).jsonObject
        if (genJson.isEmpty()) {
            userFile.copyTo(mergedFile, overwrite = true)
            return
        }
        val userFileText = userFile.readText()
        val indent = userFileText.lineSequence()
            .map { line -> line.takeWhile { it.isWhitespace() } }
            .firstOrNull { it.isNotEmpty() }
            ?: "  "
        val trailingWhitespaces = userFileText.takeLastWhile { it.isWhitespace() }
        val userJson = json.parseToJsonElement(userFileText).jsonObject
        val mergedJson = mergeConfigs(userJson, genJson)
        mergedFile.parentFile.mkdirs()
        val jsonWithIndent = Json(json) { prettyPrintIndent = indent }
        val mergedFileText = jsonWithIndent.encodeToString(JsonElement.serializer(), mergedJson)
        mergedFile.writeText(mergedFileText + trailingWhitespaces)
    }

    private fun mergeConfigs(userJson: JsonObject, genJson: JsonObject): JsonObject = buildJsonObject {
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
