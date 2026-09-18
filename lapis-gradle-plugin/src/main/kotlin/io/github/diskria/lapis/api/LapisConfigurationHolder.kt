package io.github.diskria.lapis.api

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class LapisConfigurationHolder @Inject constructor(objects: ObjectFactory) {
    val uniqueModPrefix: Property<String> = objects.property<String>()
    val mixinGeneratedSubpackage: Property<String> = objects.property<String>()
    val disableLCP: Property<Boolean> = objects.property<Boolean>()
    val nullableAnnotation: Property<String> = objects.property<String>()
    val nonNullAnnotation: Property<String> = objects.property<String>()
    val mixinAnnotation: Property<String> = objects.property<String>()
    val uniqueAnnotation: Property<String> = objects.property<String>()
    val shadowAnnotation: Property<String> = objects.property<String>()
    val mutableAnnotation: Property<String> = objects.property<String>()
    val finalAnnotation: Property<String> = objects.property<String>()
    val mixinConfig: RegularFileProperty = objects.fileProperty()
}
