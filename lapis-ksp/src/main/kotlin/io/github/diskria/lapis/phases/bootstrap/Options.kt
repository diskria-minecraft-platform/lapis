package io.github.diskria.lapis.phases.bootstrap

import kotlinx.serialization.Serializable

@Serializable
data class Options(
    val modUniquePrefix: String,
    val builtinsPackage: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String = ".generated",
    val enableFabricTweaks: Boolean = false,
    val enableForgeTweaks: Boolean = false,
)
