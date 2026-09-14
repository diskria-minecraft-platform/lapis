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
    val mixinConfig: RegularFileProperty = objects.fileProperty()
}
