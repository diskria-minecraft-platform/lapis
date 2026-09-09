package io.github.diskria.lapis

import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class MergeMixinConfigsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val userConfigFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val generatedConfigFile: RegularFileProperty

    @get:OutputFile
    abstract val mergedConfigFile: RegularFileProperty

    private val json = Json { prettyPrint = true }

    @TaskAction
    fun merge() {
        val userFile = userConfigFile.get().asFile
        val genFile = generatedConfigFile.orNull?.asFile
        val outputFile = mergedConfigFile.get().asFile
        if (genFile == null || !genFile.exists()) {
            userFile.copyTo(outputFile, overwrite = true)
            return
        }
        val userJson = json.parseToJsonElement(userFile.readText()).jsonObject
        val genJson = json.parseToJsonElement(genFile.readText()).jsonObject
        val mergedJson = mergeConfigs(userJson, genJson)
        outputFile.parentFile.mkdirs()
        outputFile.writeText(json.encodeToString(JsonElement.serializer(), mergedJson))
    }

    private fun mergeConfigs(userJson: JsonObject, generatedJson: JsonObject): JsonObject = buildJsonObject {
        val processedGenKeys = mutableSetOf<String>()
        for ((key, userValue) in userJson) {
            val generatedValue = generatedJson[key]
            if (generatedValue != null) {
                processedGenKeys.add(key)
                when {
                    userValue is JsonArray && generatedValue is JsonArray -> {
                        val mergedContent = (userValue + generatedValue).distinct()
                        put(key, JsonArray(mergedContent))
                    }

                    else -> put(key, userValue)
                }
            } else {
                put(key, userValue)
            }
        }
        generatedJson.entries.filterNot { it.key in processedGenKeys }.forEach { (key, generatedValue) ->
            put(key, generatedValue)
        }
    }
}
