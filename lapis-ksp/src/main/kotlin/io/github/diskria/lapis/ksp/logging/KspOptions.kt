package io.github.diskria.lapis.ksp.logging

import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.poetesse.extensions.doubleQuoted
import kotlinx.serialization.Serializable
import javax.lang.model.SourceVersion

@Serializable
data class KspOptions(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String? = null,
    val disableLCP: Boolean = false,
) {
    fun validate() {
        require(SourceVersion.isIdentifier(uniqueModPrefix)) {
            "Invalid ${::uniqueModPrefix.name.quoted()}: " +
                "expected a valid Java identifier name (e.g. ${"modid$".doubleQuoted()}), " +
                "but got ${uniqueModPrefix.doubleQuoted()}."
        }
        require(SourceVersion.isName(mixinPackage)) {
            "Invalid ${::mixinPackage.name.quoted()}: " +
                "expected a valid Java package name (e.g. ${"com.example.modid.mixin".doubleQuoted()}), " +
                "but got ${mixinPackage.doubleQuoted()}."
        }
        mixinGeneratedSubpackage?.let { subpackage ->
            require(SourceVersion.isName(subpackage)) {
                "Invalid ${::mixinGeneratedSubpackage.name.quoted()}: " +
                    "expected a valid Java subpackage name (e.g. ${"generated".doubleQuoted()}), " +
                    "but got ${subpackage.doubleQuoted()}."
            }
        }
    }
}
