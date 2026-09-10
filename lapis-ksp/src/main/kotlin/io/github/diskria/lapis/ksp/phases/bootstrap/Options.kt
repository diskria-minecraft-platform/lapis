package io.github.diskria.lapis.ksp.phases.bootstrap

import kotlinx.serialization.Serializable

@Serializable
data class Options(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val builtinsPackage: String = mixinPackage.takeIf { "." in it }?.substringBeforeLast(".") ?: "",
    val mixinGeneratedSubpackage: String? = null,
    val enableFabricTweaks: Boolean = false,
    val enableForgeTweaks: Boolean = false,
    val disableLCP: Boolean = false,
) {
    init {
        require(PACKAGE_REGEX.matches(mixinPackage)) {
            "Invalid 'mixinPackage': '$mixinPackage'. Must be a valid Java package name."
        }
        require(builtinsPackage.isEmpty() || PACKAGE_REGEX.matches(builtinsPackage)) {
            "Invalid 'builtinsPackage': '$builtinsPackage'. Must be a valid package name or empty for root package."
        }
        mixinGeneratedSubpackage?.let { subpackage ->
            require(PACKAGE_REGEX.matches(subpackage)) {
                "Invalid 'mixinGeneratedSubpackage': '$subpackage'. Must be a valid Java package name " +
                    "without leading or trailing dots (e.g. 'generated' or 'generated.lapis')."
            }
        }
    }

    companion object {
        private val PACKAGE_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*$""")
    }
}
