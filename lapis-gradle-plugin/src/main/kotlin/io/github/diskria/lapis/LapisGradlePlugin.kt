package io.github.diskria.lapis

import com.google.devtools.ksp.gradle.KspAATask
import io.github.diskria.lapis.api.LapisExtension
import io.github.diskria.lapis.extensions.capitalized
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import kotlin.io.path.invariantSeparatorsPathString

class LapisGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("com.google.devtools.ksp")
        val lapisExtension = project.extensions.create<LapisExtension>("lapis")
        lapisExtension.sourceSetSpecs.all { sourceSetSpec ->
            val sourceSetName = sourceSetSpec.name
            val sourceSetNamePart = if (sourceSetName == "main") "" else sourceSetName.capitalized()
            val kspTaskName = "ksp${sourceSetNamePart}Kotlin"
            val processResourcesTaskName = "process${sourceSetNamePart}Resources"
            val mergeMixinConfigsTaskName = "merge${sourceSetNamePart}MixinConfig"

            val buildDirectory = project.layout.buildDirectory
            val kspResourcesDirectory = buildDirectory.dir("generated/ksp/$sourceSetName/resources")
            val mergedResourcesDirectory = buildDirectory.dir("generated/lapis-merged/$sourceSetName/resources")

            val userConfigFile = project.providers.provider {
                val singleFile = lapisExtension.mixinConfig.orNull
                if (singleFile != null && lapisExtension.isMultiSetMode.get()) {
                    error(
                        "Conflicting mixin configuration: 'lapis.mixinConfig' property is intended for 'main' only. " +
                            "Use 'lapis.mixinConfig(sourceSet, ...)' instead when configuring multiple sourceSets."
                    )
                }
                singleFile ?: sourceSetSpec.mixinConfig.orNull
            }
            val relativePathProvider = userConfigFile.map { userFile ->
                val file = userFile.asFile
                val sourceSet = project.extensions.getByType<SourceSetContainer>().findByName(sourceSetName)
                val resourceDirs = sourceSet?.resources?.srcDirs ?: emptySet()

                resourceDirs
                    .firstOrNull { file.startsWith(it) }
                    ?.toPath()
                    ?.relativize(file.toPath())
                    ?.invariantSeparatorsPathString
                    ?: file.name
            }
            with(project.tasks) {
                withType<KspAATask>().matching { it.name == kspTaskName }.configureEach { task ->
                    task.commandLineArgumentProviders.add(
                        LapisKspArgumentProvider(
                            lapisExtension.modId,
                            lapisExtension.enableFabricTweaks,
                            lapisExtension.enableForgeTweaks,
                            userConfigFile,
                        )
                    )
                }
                val mergeMixinConfigsTask = register(
                    mergeMixinConfigsTaskName, MergeMixinConfigsTask::class.java
                ) { task ->
                    task.dependsOn(kspTaskName)
                    task.userConfigFile.set(userConfigFile)
                    task.generatedConfigFile.set(kspResourcesDirectory.get().file("lapis-intermediates/mixins.json"))
                    task.mergedConfigFile.set(
                        relativePathProvider.flatMap { relativePath ->
                            mergedResourcesDirectory.map { it.file(relativePath) }
                        }
                    )
                }
                withType<ProcessResources>().matching { it.name == processResourcesTaskName }.configureEach { task ->
                    val kspResourcesDirectoryFile = kspResourcesDirectory.get().asFile
                    val mergedResourcesDirectoryFile = mergedResourcesDirectory.get().asFile
                    task.exclude { element ->
                        if (element.file.startsWith(kspResourcesDirectoryFile)) return@exclude true
                        if (element.file.startsWith(mergedResourcesDirectoryFile)) return@exclude false
                        val relativePath = relativePathProvider.orNull ?: return@exclude false
                        element.relativePath.pathString == relativePath
                    }
                    task.from(mergeMixinConfigsTask.flatMap { mergeTask ->
                        mergeTask.mergedConfigFile.zip(relativePathProvider) { outputFile, relativePath ->
                            val resourcesPath = outputFile.asFile.absolutePath.removeSuffix(relativePath)
                            project.layout.projectDirectory.dir(resourcesPath)
                        }
                    })
                }
            }
        }
    }
}
