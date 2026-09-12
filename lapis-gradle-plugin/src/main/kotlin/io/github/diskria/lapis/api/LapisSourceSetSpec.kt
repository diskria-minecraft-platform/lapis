package io.github.diskria.lapis.api

import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class LapisSourceSetSpec @Inject constructor(val name: String, objects: ObjectFactory) {
    val mixinConfig = objects.fileProperty()
}
