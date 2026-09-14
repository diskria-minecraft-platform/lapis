package io.github.diskria.lapis.api

import io.github.diskria.lapis.extensions.getRootRelativePath
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

abstract class LapisExtension @Inject internal constructor(
    objects: ObjectFactory,
    private val layout: ProjectLayout,
) : LapisConfigurationHolder(objects), LapisDslScope {

    internal val sourceSetSpecs: NamedDomainObjectContainer<LapisSourceSetSpec> =
        objects.domainObjectContainer(LapisSourceSetSpec::class.java).apply {
            maybeCreate("main")
        }

    fun sourceSet(name: String, configure: LapisSourceSetSpec.() -> Unit = {}): LapisSourceSetSpec =
        sourceSetSpecs.maybeCreate(name).apply { configure() }

    internal fun validateMixinConfig() {
        require(sourceSetSpecs.size <= 1 || !mixinConfig.isPresent) {
            "Cannot configure 'lapis.mixinConfig' property " +
                "when multiple sourceSets exist (${sourceSetSpecs.names.joinToString()}). " +
                "Configure 'mixinConfig' inside individual sourceSet specs instead."
        }
        val pathDuplicates = sourceSetSpecs.mapNotNull { spec ->
            spec.mixinConfig.orNull?.asFile?.let { spec.name to it.getRootRelativePath(layout) }
        }.groupBy({ it.second }, { it.first }).filter { it.value.size > 1 }
        require(pathDuplicates.isEmpty()) {
            pathDuplicates.entries.joinToString("\n\n") { (path, sourceSetNames) ->
                "Duplicate 'mixinConfig' file detected: " +
                    "'$path' is configured in multiple sourceSets (${sourceSetNames.joinToString()}). " +
                    "Each sourceSet must have a unique 'mixinConfig' file."
            }
        }
    }
}
