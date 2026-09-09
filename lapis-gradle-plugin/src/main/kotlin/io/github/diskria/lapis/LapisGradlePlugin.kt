package io.github.diskria.lapis

import com.google.devtools.ksp.gradle.KspAATask
import io.github.diskria.lapis.api.LapisExtension
import io.github.diskria.lapis.extensions.capitalized
import io.github.diskria.lapis.extensions.register
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
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
            val kspConfigurationName = "ksp${sourceSetNamePart}"
            val kspTaskName = "${kspConfigurationName}Kotlin"
            val processResourcesTaskName = "process${sourceSetNamePart}Resources"
            val mergeMixinConfigsTaskName = "merge${sourceSetNamePart}MixinConfigs"

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
            val relativePathProvider = userConfigFile.map { file ->
                val userConfigPath = file.asFile.toPath()
                project.extensions.getByType<SourceSetContainer>()
                    .findByName(sourceSetName)
                    ?.resources
                    ?.srcDirs
                    ?.firstOrNull { userConfigPath.startsWith(it.toPath()) }
                    ?.toPath()
                    ?.relativize(userConfigPath)
                    ?.invariantSeparatorsPathString
                    ?: userConfigPath.fileName.toString()
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
                val mergeMixinConfigsTask = register<MergeMixinConfigsTask>(name = mergeMixinConfigsTaskName) { task ->
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
                    task.from(
                        mergeMixinConfigsTask.flatMap {
                            it.mergedConfigFile.zip(relativePathProvider) { outputFile, relativePath ->
                                val resourcesPath = outputFile.asFile.absolutePath.removeSuffix(relativePath)
                                project.layout.projectDirectory.dir(resourcesPath)
                            }
                        }
                    )
                }
            }
            project.configurations.matching { it.name == kspConfigurationName }.configureEach { configuration ->
                val dependency = project.dependencies.create("io.github.diskria:lapis-ksp:$PLUGIN_VERSION")
                configuration.dependencies.add(dependency)
            }
        }
        project.dependencies {
            "compileOnly"("io.github.diskria:lapis-annotations:$PLUGIN_VERSION")
        }
    }

    private companion object {
        const val PLUGIN_VERSION = "0.9.1"
    }
}
