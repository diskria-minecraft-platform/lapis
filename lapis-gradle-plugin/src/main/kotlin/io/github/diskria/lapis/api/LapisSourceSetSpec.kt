package io.github.diskria.lapis.api

import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import javax.inject.Inject

abstract class LapisSourceSetSpec @Inject constructor(
    val name: String,
    objects: ObjectFactory,
) : LapisConfigurationHolder(objects) {

    fun getEffectiveUniqueModPrefix(root: LapisConfigurationHolder): Provider<String> =
        uniqueModPrefix.orElse(root.uniqueModPrefix)

    fun getEffectiveMixinGeneratedSubpackage(root: LapisConfigurationHolder): Provider<String> =
        mixinGeneratedSubpackage.orElse(root.mixinGeneratedSubpackage)

    fun getEffectiveDisableLCP(root: LapisConfigurationHolder): Provider<Boolean> =
        disableLCP.orElse(root.disableLCP)

    fun getEffectiveMixinConfig(root: LapisConfigurationHolder): Provider<RegularFile> =
        mixinConfig.orElse(root.mixinConfig)
}
