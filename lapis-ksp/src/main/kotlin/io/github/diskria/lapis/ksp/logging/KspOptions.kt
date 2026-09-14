package io.github.diskria.lapis.ksp.logging

import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.poetesse.extensions.doubleQuoted
import kotlinx.serialization.Serializable

@Serializable
data class KspOptions(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String? = null,
    val disableLCP: Boolean = false,
) {
    fun validate() {
        require(IDENTIFIER_REGEX.matches(uniqueModPrefix)) {
            "Invalid ${::uniqueModPrefix.name.quoted()}: expected a valid Java identifier name, " +
                "but got ${uniqueModPrefix.doubleQuoted()}."
        }
        require(PACKAGE_REGEX.matches(mixinPackage)) {
            "Invalid ${::mixinPackage.name.quoted()}: expected a valid Java package name, " +
                "but got ${mixinPackage.doubleQuoted()}."
        }
        mixinGeneratedSubpackage?.let { subpackage ->
            require(PACKAGE_REGEX.matches(subpackage)) {
                "Invalid ${::mixinGeneratedSubpackage.name.quoted()}: expected a valid Java package name " +
                    "without leading or trailing dots (e.g. 'generated'), " +
                    "but got ${subpackage.doubleQuoted()}."
            }
        }
    }

    companion object {
        private val IDENTIFIER_REGEX = Regex("""^[a-zA-Z_$][a-zA-Z0-9_$]*$""")
        private val PACKAGE_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*$""")
    }
}
