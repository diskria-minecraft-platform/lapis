package io.github.diskria.lapis.api

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

abstract class LapisExtension @Inject internal constructor(objects: ObjectFactory) : LapisDslScope {

    val uniqueModPrefix = objects.property<String>()
    val enableFabricTweaks = objects.property<Boolean>()
    val enableForgeTweaks = objects.property<Boolean>()
    val disableLCP = objects.property<Boolean>()
    val sourceSets = objects.domainObjectContainer(LapisSourceSetSpec::class.java)

    fun sourceSets(configure: NamedDomainObjectContainer<LapisSourceSetSpec>.() -> Unit) {
        configure(sourceSets)
    }
}
