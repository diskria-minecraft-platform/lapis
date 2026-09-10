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
        val userJson = json.parseToJsonElement(userFile.readText()).jsonObject
        val genJson = json.parseToJsonElement(genFile.readText()).jsonObject
        val mergedJson = mergeConfigs(userJson, genJson)
        mergedFile.parentFile.mkdirs()
        mergedFile.writeText(json.encodeToString(JsonElement.serializer(), mergedJson))
    }

    private fun mergeConfigs(userJson: JsonObject, genJson: JsonObject): JsonObject = buildJsonObject {
        val processedGenKeys = mutableSetOf<String>()
        userJson.forEach { (key, userValue) ->
            val genValue = genJson[key]
            val mergedValue = if (genValue != null) {
                processedGenKeys.add(key)
                when {
                    userValue is JsonArray && genValue is JsonArray -> JsonArray(userValue + genValue)
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
