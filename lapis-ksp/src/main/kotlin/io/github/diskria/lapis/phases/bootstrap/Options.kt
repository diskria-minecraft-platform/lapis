package io.github.diskria.lapis.phases.bootstrap

import kotlinx.serialization.Serializable

@Serializable
data class Options(
    val modUniquePrefix: String,
    val mixinPackage: String,
    val builtinsPackage: String = mixinPackage.substringBeforeLast("."),
    val mixinGeneratedSubpackage: String? = null,
    val enableFabricTweaks: Boolean = false,
    val enableForgeTweaks: Boolean = false,
)
