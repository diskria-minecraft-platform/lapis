package io.github.diskria.lapis

import com.google.devtools.ksp.gradle.KspAATask
import io.github.diskria.lapis.api.LapisExtension
import io.github.diskria.lapis.api.LapisSourceSetSpec
import io.github.diskria.lapis.extensions.capitalized
import io.github.diskria.lapis.extensions.register
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
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
        lapisExtension.sourceSetSpecs.all { spec ->
            val sourceSets = project.extensions.getByType<SourceSetContainer>()
            sourceSets.matching { it.name == spec.name }.configureEach { sourceSet ->
                configureSourceSet(project, sourceSet, spec, lapisExtension)
            }
        }
        project.afterEvaluate {
            lapisExtension.validateMixinConfigsSetup()
        }
    }

    private fun configureSourceSet(
        project: Project,
        sourceSet: SourceSet,
        spec: LapisSourceSetSpec,
        lapisExtension: LapisExtension,
    ) {
        addLapisDependency(project, sourceSet.kspConfigurationName, "lapis-ksp")
        addLapisDependency(project, sourceSet.compileClasspathConfigurationName, "lapis-annotations")

        val mixinConfig = spec.getEffectiveMixinConfig(lapisExtension)

        val kspTaskName = sourceSet.getTaskName("ksp", "kotlin")
        project.tasks.withType<KspAATask>().matching { it.name == kspTaskName }.configureEach { kspTask ->
            kspTask.commandLineArgumentProviders.add(
                project.objects.newInstance<KspArgumentProvider>().apply {
                    uniqueModPrefix.set(spec.getEffectiveUniqueModPrefix(lapisExtension))
                    mixinGeneratedSubpackage.set(spec.getEffectiveMixinGeneratedSubpackage(lapisExtension))
                    disableLCP.set(spec.getEffectiveDisableLCP(lapisExtension))
                    nullableAnnotation.set(spec.getEffectiveNullableAnnotation(lapisExtension))
                    nonNullAnnotation.set(spec.getEffectiveNonNullAnnotation(lapisExtension))
                    mixinAnnotation.set(spec.getEffectiveMixinAnnotation(lapisExtension))
                    uniqueAnnotation.set(spec.getEffectiveUniqueAnnotation(lapisExtension))
                    shadowAnnotation.set(spec.getEffectiveShadowAnnotation(lapisExtension))
                    mutableAnnotation.set(spec.getEffectiveMutableAnnotation(lapisExtension))
                    finalAnnotation.set(spec.getEffectiveFinalAnnotation(lapisExtension))
                    mixinAnnotationPackages.set(spec.getEffectiveMixinAnnotationPackages(lapisExtension))
                    this.mixinConfig.set(mixinConfig)
                }
            )
        }

        val relativePathProvider = mixinConfig.map {
            val userConfigPath = it.asFile.toPath()
            sourceSet.resources.srcDirs.firstOrNull { srcDir -> userConfigPath.startsWith(srcDir.toPath()) }
                ?.toPath()
                ?.relativize(userConfigPath)
                ?.invariantSeparatorsPathString
                ?: userConfigPath.fileName.toString()
        }
        val buildDirectory = project.layout.buildDirectory
        val kspResourcesDirectory = buildDirectory.dir("generated/ksp/${sourceSet.name}/resources")
        val mergedResourcesDirectory = buildDirectory.dir("generated/lapis-merged/${sourceSet.name}/resources")

        val mergeMixinConfigsTask = project.tasks.register<MergeMixinConfigsTask>(
            name = sourceSet.getTaskName("merge", "mixinConfigs")
        ) { mergeMixinConfigsTask ->
            mergeMixinConfigsTask.dependsOn(kspTaskName)
            mergeMixinConfigsTask.userConfig.set(mixinConfig)
            mergeMixinConfigsTask.generatedConfig.set(
                kspResourcesDirectory.map { it.file("lapis-intermediates/generated-mixins.json") }
            )
            mergeMixinConfigsTask.mergedConfig.set(
                relativePathProvider.flatMap { relativePath ->
                    mergedResourcesDirectory.map { it.file(relativePath) }
                }
            )
        }
        project.tasks.withType<ProcessResources>()
            .matching { it.name == sourceSet.processResourcesTaskName }
            .configureEach { processResourcesTask ->
                processResourcesTask.exclude { element ->
                    kspResourcesDirectory.orNull?.asFile?.let {
                        if (element.file.startsWith(it)) return@exclude true
                    }
                    mergedResourcesDirectory.orNull?.asFile?.let {
                        if (element.file.startsWith(it)) return@exclude false
                    }
                    val relativePath = relativePathProvider.orNull ?: return@exclude false
                    element.relativePath.pathString == relativePath
                }
                processResourcesTask.from(
                    mergeMixinConfigsTask.flatMap {
                        it.mergedConfig.zip(relativePathProvider) { outputFile, relativePath ->
                            val resourcesPath = outputFile.asFile.absolutePath.removeSuffix(relativePath)
                            project.layout.projectDirectory.dir(resourcesPath)
                        }
                    }
                )
            }
    }

    private fun addLapisDependency(project: Project, targetConfigurationName: String, artifactId: String) {
        val lapisConfigurationName = "lapis${targetConfigurationName.capitalized()}"
        val lapisConfiguration = project.configurations.maybeCreate(lapisConfigurationName).apply {
            dependencies.add(project.dependencies.create("io.github.diskria:$artifactId:$PLUGIN_VERSION"))
        }
        project.configurations.matching { it.name == targetConfigurationName }.configureEach { targetConfiguration ->
            targetConfiguration.extendsFrom(lapisConfiguration)
        }
    }

    private companion object {
        const val PLUGIN_VERSION = "0.10.0-SNAPSHOT"
    }
}

private val SourceSet.kspConfigurationName: String get() = getTaskName("ksp", "")
