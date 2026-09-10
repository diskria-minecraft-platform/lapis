package io.github.diskria.lapis

import com.google.devtools.ksp.gradle.KspAATask
import io.github.diskria.lapis.api.LapisExtension
import io.github.diskria.lapis.extensions.capitalized
import io.github.diskria.lapis.extensions.decapitalized
import io.github.diskria.lapis.extensions.register
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import kotlin.io.path.invariantSeparatorsPathString

class LapisGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.apply("com.google.devtools.ksp")
        val lapisExtension = project.extensions.create<LapisExtension>("lapis")
        lapisExtension.sourceSets.all { sourceSetSpec ->
            val sourceSetName = sourceSetSpec.name
            val sourceSetNamePart = if (sourceSetName == "main") "" else sourceSetName.capitalized()

            val kspConfigurationName = "ksp${sourceSetNamePart}"
            val lapisKspConfigurationName = "lapis${kspConfigurationName.capitalized()}"
            val lapisKspConfiguration = project.configurations.maybeCreate(lapisKspConfigurationName).apply {
                dependencies.add(project.dependencies.create("io.github.diskria:lapis-ksp:$PLUGIN_VERSION"))
            }
            project.configurations.matching { it.name == kspConfigurationName }.configureEach { kspConfiguration ->
                kspConfiguration.extendsFrom(lapisKspConfiguration)
            }

            val compileClasspathName = "${sourceSetNamePart}CompileClasspath".decapitalized()
            val lapisCompileClasspathName = "lapis${compileClasspathName.capitalized()}"
            val lapisCompileClasspath = project.configurations.maybeCreate(lapisCompileClasspathName).apply {
                dependencies.add(project.dependencies.create("io.github.diskria:lapis-annotations:$PLUGIN_VERSION"))
            }
            project.configurations.matching { it.name == compileClasspathName }.configureEach { compileClasspath ->
                compileClasspath.extendsFrom(lapisCompileClasspath)
            }

            val buildDirectory = project.layout.buildDirectory
            val kspResourcesDirectory = buildDirectory.dir("generated/ksp/$sourceSetName/resources")
            val mergedResourcesDirectory = buildDirectory.dir("generated/lapis-merged/$sourceSetName/resources")
            val relativePathProvider = sourceSetSpec.mixinConfig.map { config ->
                val userConfigPath = config.asFile.toPath()
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
                val kspTaskName = "${kspConfigurationName}Kotlin"
                val mergeMixinConfigsTaskName = "merge${sourceSetNamePart}MixinConfigs"
                val processResourcesTaskName = "process${sourceSetNamePart}Resources"
                withType<KspAATask>().matching { it.name == kspTaskName }.configureEach { task ->
                    task.commandLineArgumentProviders.add(
                        project.objects.newInstance<LapisKspArgumentProvider>(
                            lapisExtension.modId,
                            lapisExtension.enableFabricTweaks,
                            lapisExtension.enableForgeTweaks,
                            lapisExtension.disableLCP,
                            sourceSetSpec.mixinConfig,
                            sourceSetSpec.builtinsPackage,
                        )
                    )
                }
                val mergeMixinConfigsTask = register<MergeMixinConfigsTask>(name = mergeMixinConfigsTaskName) { task ->
                    task.dependsOn(kspTaskName)
                    task.userConfig.set(sourceSetSpec.mixinConfig)
                    task.generatedConfig.set(kspResourcesDirectory.get().file("lapis-intermediates/mixins.json"))
                    task.mergedConfig.set(
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
                            it.mergedConfig.zip(relativePathProvider) { outputFile, relativePath ->
                                val resourcesPath = outputFile.asFile.absolutePath.removeSuffix(relativePath)
                                project.layout.projectDirectory.dir(resourcesPath)
                            }
                        }
                    )
                }
            }
        }
    }

    private companion object {
        const val PLUGIN_VERSION = "0.9.1"
    }
}
