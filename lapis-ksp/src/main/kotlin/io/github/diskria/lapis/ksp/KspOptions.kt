package io.github.diskria.lapis.ksp

import io.github.diskria.lapis.ksp.extensions.quoted
import kotlinx.serialization.Serializable
import javax.lang.model.SourceVersion

@Serializable
data class KspOptions(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String? = null,
    val disableLCP: Boolean = false,
) {
    fun validate(): List<String> = buildList {
        if (!SourceVersion.isIdentifier(uniqueModPrefix)) {
            add(
                "Invalid '${::uniqueModPrefix.name}': " +
                    "expected a valid Java identifier name (e.g. ${"modid$".quoted()}), " +
                    "but got ${uniqueModPrefix.quoted()}."
            )
        }
        if (!SourceVersion.isName(mixinPackage)) {
            add(
                "Invalid '${::mixinPackage.name}': " +
                    "expected a valid Java package name (e.g. ${"com.example.modid.mixin".quoted()}), " +
                    "but got ${mixinPackage.quoted()}."
            )
        }
        mixinGeneratedSubpackage?.let { subpackage ->
            if (!SourceVersion.isName(subpackage)) {
                add(
                    "Invalid '${::mixinGeneratedSubpackage.name}': " +
                        "expected a valid Java subpackage name (e.g. ${"generated".quoted()}), " +
                        "but got ${subpackage.quoted()}."
                )
            }
        }
    }
}
