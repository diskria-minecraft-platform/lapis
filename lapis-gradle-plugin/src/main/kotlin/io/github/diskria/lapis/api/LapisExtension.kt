package io.github.diskria.lapis.api

import org.gradle.api.file.FileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.SourceSet
import org.gradle.kotlin.dsl.property
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

abstract class LapisExtension @Inject internal constructor(
    private val objects: ObjectFactory,
    private val providers: ProviderFactory,
    private val layout: ProjectLayout,
) : LapisDslScope {

    val modId = objects.property<String>()
    val enableFabricTweaks = objects.property<Boolean>()
    val enableForgeTweaks = objects.property<Boolean>()
    val mixinConfig = objects.fileProperty()

    internal val sourceSetSpecs = objects.domainObjectContainer(LapisSourceSetSpec::class.java)
    internal val isMultiSetMode = objects.property<Boolean>().convention(false)

    init {
        sourceSetSpecs.maybeCreate("main").mixinConfig.convention(mixinConfig)
    }

    fun mixinConfig(sourceSetName: String, configFile: Any) {
        isMultiSetMode.set(true)
        val fileProvider = objects.asRegularFile(layout, providers, configFile)
        sourceSetSpecs.maybeCreate(sourceSetName).mixinConfig.set(fileProvider)
    }

    fun mixinConfig(sourceSet: SourceSet, configFile: Any) {
        mixinConfig(sourceSet.name, configFile)
    }
}

internal fun ObjectFactory.asRegularFile(
    layout: ProjectLayout,
    providers: ProviderFactory,
    notation: Any
): Provider<RegularFile> {
    val tempProperty = fileProperty()
    return when (notation) {
        is Provider<*> -> notation.flatMap { asRegularFile(layout, providers, it) }
        is FileCollection -> tempProperty.fileProvider(providers.provider { notation.singleFile })
        is RegularFile -> providers.provider { notation }
        is File -> tempProperty.apply { set(notation) }
        is Path -> tempProperty.apply { set(notation.toFile()) }
        else -> providers.provider { layout.projectDirectory.file(notation.toString()) }
    }
}
