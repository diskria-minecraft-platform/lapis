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
        val userFile = userConfig.get().asFile
        val genFile = generatedConfig.orNull?.asFile
        val mergedFile = mergedConfig.get().asFile
        if (genFile == null || !genFile.exists()) {
            userFile.copyTo(mergedFile, overwrite = true)
            return
        }
        val genFileText = genFile.readText()
        val genJson = Json.parseToJsonElement(genFileText).jsonObject
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
        val userJson = Json.parseToJsonElement(userFileText).jsonObject
        val mergedJson = mergeJsonObjects(userJson, genJson)
        val jsonForOutput = Json {
            prettyPrint = true
            prettyPrintIndent = indent
        }
        val mergedFileText = jsonForOutput.encodeToString(JsonElement.serializer(), mergedJson)
        mergedFile.parentFile.mkdirs()
        mergedFile.writeText(mergedFileText + trailingWhitespaces)
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
