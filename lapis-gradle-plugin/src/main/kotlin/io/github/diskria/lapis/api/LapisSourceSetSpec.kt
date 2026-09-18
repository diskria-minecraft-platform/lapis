package io.github.diskria.lapis.api

import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import javax.inject.Inject

abstract class LapisSourceSetSpec @Inject constructor(
    val name: String,
    objects: ObjectFactory,
) : LapisConfigurationHolder(objects) {

    fun getEffectiveUniqueModPrefix(lapisExtension: LapisExtension): Provider<String> =
        uniqueModPrefix.orElse(lapisExtension.uniqueModPrefix)

    fun getEffectiveMixinGeneratedSubpackage(lapisExtension: LapisExtension): Provider<String> =
        mixinGeneratedSubpackage.orElse(lapisExtension.mixinGeneratedSubpackage)

    fun getEffectiveDisableLCP(lapisExtension: LapisExtension): Provider<Boolean> =
        disableLCP.orElse(lapisExtension.disableLCP)

    fun getEffectiveNullableAnnotation(lapisExtension: LapisExtension): Provider<String> =
        nullableAnnotation.orElse(lapisExtension.nullableAnnotation)

    fun getEffectiveNonNullAnnotation(lapisExtension: LapisExtension): Provider<String> =
        nonNullAnnotation.orElse(lapisExtension.nonNullAnnotation)

    fun getEffectiveMixinConfig(lapisExtension: LapisExtension): Provider<RegularFile> =
        mixinConfig.orElse(lapisExtension.mixinConfig)
}
