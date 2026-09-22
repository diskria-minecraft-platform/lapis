package io.github.diskria.lapis.api

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class LapisConfigurationHolder {

    abstract val uniqueModPrefix: Property<String>
    abstract val mixinGeneratedSubpackage: Property<String>
    abstract val disableLCP: Property<Boolean>
    abstract val nullableAnnotation: Property<String>
    abstract val nonNullAnnotation: Property<String>
    abstract val mixinAnnotation: Property<String>
    abstract val uniqueAnnotation: Property<String>
    abstract val shadowAnnotation: Property<String>
    abstract val mutableAnnotation: Property<String>
    abstract val finalAnnotation: Property<String>
    abstract val mixinConfig: RegularFileProperty

    internal abstract val mixinAnnotationPackages: ListProperty<String>

    fun mixinAnnotationPackages(packages: Iterable<String>) {
        mixinAnnotationPackages.set(packages.toList())
    }

    fun mixinAnnotationPackages(vararg packages: String) {
        mixinAnnotationPackages(packages.toList())
    }
}
