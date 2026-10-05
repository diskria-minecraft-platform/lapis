package io.github.diskria.lapis.api

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

abstract class LapisSourceSetSpec @Inject constructor(val name: String) : LapisConfigurationHolder() {

    fun effective(ext: LapisExtension) = Effective(ext)

    inner class Effective(private val ext: LapisExtension) {

        val uniqueModPrefix get() = resolveProperty { uniqueModPrefix }
        val mixinGeneratedSubpackage get() = resolveProperty { mixinGeneratedSubpackage }
        val disableLCP get() = resolveProperty { disableLCP }
        val nullableAnnotation get() = resolveProperty { nullableAnnotation }
        val nonNullAnnotation get() = resolveProperty { nonNullAnnotation }
        val mixinAnnotation get() = resolveProperty { mixinAnnotation }
        val uniqueAnnotation get() = resolveProperty { uniqueAnnotation }
        val shadowAnnotation get() = resolveProperty { shadowAnnotation }
        val mutableAnnotation get() = resolveProperty { mutableAnnotation }
        val finalAnnotation get() = resolveProperty { finalAnnotation }
        val mixinAnnotationPackages get() = resolveListProperty { mixinAnnotationPackages }
        val mixinConfig get() = resolveProperty { mixinConfig }

        private inline fun <T : Any> resolveProperty(property: LapisConfigurationHolder.() -> Property<T>) =
            this@LapisSourceSetSpec.property().orElse(ext.property())

        private inline fun <T : Any> resolveListProperty(property: LapisConfigurationHolder.() -> ListProperty<T>) =
            this@LapisSourceSetSpec.property().orElseIfEmpty(ext.property())
    }
}

private fun <T : Collection<*>> Provider<T>.orElseIfEmpty(fallback: Provider<T>): Provider<T> =
    flatMap { if (it.isEmpty()) fallback else this }
