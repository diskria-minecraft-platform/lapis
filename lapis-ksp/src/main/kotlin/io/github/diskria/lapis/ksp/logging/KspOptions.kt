package io.github.diskria.lapis.ksp.logging

import kotlinx.serialization.Serializable

@Serializable
data class KspOptions(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String? = null,
    val disableLCP: Boolean = false,
) {
    init {
        require(IDENTIFIER_REGEX.matches(uniqueModPrefix)) {
            "Invalid 'uniqueModPrefix': '$uniqueModPrefix'. Must be a valid Java identifier name."
        }
        require(PACKAGE_REGEX.matches(mixinPackage)) {
            "Invalid 'mixinPackage': '$mixinPackage'. Must be a valid Java package name."
        }
        mixinGeneratedSubpackage?.let { subpackage ->
            require(PACKAGE_REGEX.matches(subpackage)) {
                "Invalid 'mixinGeneratedSubpackage': '$subpackage'. Must be a valid Java package name " +
                    "without leading or trailing dots (e.g. 'generated')."
            }
        }
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("""^[a-zA-Z_$][a-zA-Z0-9_$]*$""")
        private val PACKAGE_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*$""")
    }
}
