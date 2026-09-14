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
        val errors = buildList {
            if (!SourceVersion.isIdentifier(uniqueModPrefix)) {
                add(
                    "Invalid ${::uniqueModPrefix.name.quoted()}: " +
                        "expected a valid Java identifier name (e.g. ${"modid$".doubleQuoted()}), " +
                        "but got ${uniqueModPrefix.doubleQuoted()}."
                )
            }
            if (!SourceVersion.isName(mixinPackage)) {
                add(
                    "Invalid ${::mixinPackage.name.quoted()}: " +
                        "expected a valid Java package name (e.g. ${"com.example.modid.mixin".doubleQuoted()}), " +
                        "but got ${mixinPackage.doubleQuoted()}."
                )
            }
            mixinGeneratedSubpackage?.let { subpackage ->
                if (!SourceVersion.isName(subpackage)) {
                    add(
                        "Invalid ${::mixinGeneratedSubpackage.name.quoted()}: " +
                            "expected a valid Java subpackage name (e.g. ${"generated".doubleQuoted()}), " +
                            "but got ${subpackage.doubleQuoted()}."
                    )
                }
            }
        }
        require(errors.isEmpty()) { errors.joinToString("\n") }
    }
}
