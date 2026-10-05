package io.github.diskria.lapis

import com.google.devtools.ksp.gradle.KspAATask
import io.github.diskria.lapis.api.LapisExtension
import io.github.diskria.lapis.api.LapisSourceSetSpec
import io.github.diskria.lapis.extensions.capitalized
import io.github.diskria.lapis.extensions.register
import io.github.diskria.lapis.tasks.MergeMixinConfigsTask
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import kotlin.io.path.invariantSeparatorsPathString

class LapisGradlePlugin : KspSupportPlugin() {

    override fun apply(target: Project) {
        super.apply(target)
        val ext = target.extensions.create<LapisExtension>("lapis")
        ext.sourceSetSpecs.all { spec ->
            val sourceSets = target.extensions.getByType<SourceSetContainer>()
            sourceSets.matching { it.name == spec.name }.configureEach { ss ->
                target.addLapisDependencyTo(ss.compileClasspathConfigurationName, "lapis-annotations")
                target.setupKsp(ss, spec.effective(ext))
            }
        }
        target.afterEvaluate { ext.validateMixinConfigsSetup() }
    }
}

open class KspSupportPlugin : KcpSupportPlugin() {

    override fun apply(target: Project) {
        super.apply(target)
        target.pluginManager.apply("com.google.devtools.ksp")
    }

    protected fun Project.setupKsp(ss: SourceSet, effective: LapisSourceSetSpec.Effective) {
        project.addLapisDependencyTo(ss.kspConfigurationName, "lapis-ksp")

        val kspTaskName = ss.getTaskName("ksp", "kotlin")
        project.tasks.withType<KspAATask>().matching { it.name == kspTaskName }.configureEach { kspTask ->
            kspTask.commandLineArgumentProviders.add(project.createCliArgumentProvider(effective))
        }

        val mixinConfig = effective.mixinConfig
        val relativePathProvider = mixinConfig.map {
            val userConfigPath = it.asFile.toPath()
            ss.resources.srcDirs.firstOrNull { srcDir -> userConfigPath.startsWith(srcDir.toPath()) }
                ?.toPath()
                ?.relativize(userConfigPath)
                ?.invariantSeparatorsPathString
                ?: userConfigPath.fileName.toString()
        }
        val buildDir = project.layout.buildDirectory
        val kspResDir = buildDir.dir("generated/ksp/${ss.name}/resources")
        val mergedResDir = buildDir.dir("generated/lapis-merged/${ss.name}/resources")

        val mergeMixinConfigs = ss.getTaskName("merge", "mixinConfigs")
        val mergeMixinConfigsTask = project.tasks.register<MergeMixinConfigsTask>(name = mergeMixinConfigs) { task ->
            task.dependsOn(kspTaskName)
            task.userConfig.set(mixinConfig)
            task.generatedConfig.set(kspResDir.map { it.file("lapis-intermediates/generated-mixins.json") })
            task.mergedConfig.set(relativePathProvider.flatMap { mergedResDir.map { dir -> dir.file(it) } })
        }
        val processResources = ss.processResourcesTaskName
        project.tasks.withType<ProcessResources>().matching { it.name == processResources }.configureEach { task ->
            task.exclude { element ->
                kspResDir.orNull?.asFile?.let {
                    if (element.file.startsWith(it)) return@exclude true
                }
                mergedResDir.orNull?.asFile?.let {
                    if (element.file.startsWith(it)) return@exclude false
                }
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

    private val SourceSet.kspConfigurationName: String get() = getTaskName("ksp", "")
}

open class KcpSupportPlugin : KotlinCompilerPluginSupportPlugin {

    override fun getCompilerPluginId() = "io.github.diskria.lapis.kcp"
    override fun getPluginArtifact() = SubpluginArtifact(GROUP_ID, "lapis-kcp", VERSION)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val ext = project.extensions.findByType<LapisExtension>() ?: return false
        return kotlinCompilation.compilationName in ext.sourceSetSpecs.names
    }

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val ext = project.extensions.getByType<LapisExtension>()
        return project.provider {
            val effective = ext.sourceSetSpecs.getByName(kotlinCompilation.compilationName).effective(ext)
            project.createCliArgumentProvider(effective).asKcpArguments()
        }
    }

    protected fun Project.addLapisDependencyTo(targetConfigName: String, artifactId: String) {
        val superConfig = project.configurations.maybeCreate("lapis${targetConfigName.capitalized()}").apply {
            dependencies.add(project.dependencies.create("$GROUP_ID:$artifactId:$VERSION"))
        }
        project.configurations.matching { it.name == targetConfigName }.configureEach { it.extendsFrom(superConfig) }
    }

    protected companion object {
        const val GROUP_ID = "io.github.diskria"
        const val VERSION = "0.10.0-SNAPSHOT"
    }
}

private fun Project.createCliArgumentProvider(effective: LapisSourceSetSpec.Effective): CliArgumentProvider =
    objects.newInstance<CliArgumentProvider>().apply {
        uniqueModPrefix.set(effective.uniqueModPrefix)
        mixinGeneratedSubpackage.set(effective.mixinGeneratedSubpackage)
        disableLCP.set(effective.disableLCP)
        nullableAnnotation.set(effective.nullableAnnotation)
        nonNullAnnotation.set(effective.nonNullAnnotation)
        mixinAnnotation.set(effective.mixinAnnotation)
        uniqueAnnotation.set(effective.uniqueAnnotation)
        shadowAnnotation.set(effective.shadowAnnotation)
        mutableAnnotation.set(effective.mutableAnnotation)
        finalAnnotation.set(effective.finalAnnotation)
        mixinAnnotationPackages.set(effective.mixinAnnotationPackages)
        mixinConfig.set(effective.mixinConfig)
    }
