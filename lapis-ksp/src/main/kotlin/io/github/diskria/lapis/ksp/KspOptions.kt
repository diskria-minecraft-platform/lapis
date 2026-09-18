package io.github.diskria.lapis.ksp

import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.poetesse.java.JPClassName
import kotlinx.serialization.Serializable
import javax.lang.model.SourceVersion
import kotlin.reflect.KProperty0

@Serializable
data class KspOptions(
    val uniqueModPrefix: String,
    val mixinPackage: String,
    val mixinGeneratedSubpackage: String? = null,
    val disableLCP: Boolean = false,
    val nullableAnnotation: String? = null,
    val nonNullAnnotation: String? = null,
    val mixinAnnotation: String = MIXIN_ANNOTATION,
    val uniqueAnnotation: String = UNIQUE_ANNOTATION,
    val shadowAnnotation: String = SHADOW_ANNOTATION,
    val mutableAnnotation: String = MUTABLE_ANNOTATION,
    val finalAnnotation: String = FINAL_ANNOTATION,
) {
    fun validate(): List<String> = buildList {
        validateJavaIdentifierName(::uniqueModPrefix, "modid$")
        validateJavaPackageName(::mixinPackage, "com.example.modid.mixin")
        validateJavaPackageName(::mixinGeneratedSubpackage, "generated")
        validateJavaClassName(::nullableAnnotation, "org.jspecify.annotations.Nullable")
        validateJavaClassName(::nonNullAnnotation, "org.jspecify.annotations.NonNull")
        validateJavaClassName(::mixinAnnotation, MIXIN_ANNOTATION)
        validateJavaClassName(::uniqueAnnotation, UNIQUE_ANNOTATION)
        validateJavaClassName(::shadowAnnotation, SHADOW_ANNOTATION)
        validateJavaClassName(::mutableAnnotation, MUTABLE_ANNOTATION)
        validateJavaClassName(::finalAnnotation, FINAL_ANNOTATION)
    }

    private fun MutableList<String>.validateJavaIdentifierName(property: KProperty0<String?>, example: String) {
        val value = property.get() ?: return
        if (!SourceVersion.isIdentifier(value)) {
            add(
                "Invalid '${property.name}': expected a valid Java identifier name " +
                    "(e.g. ${example.quoted()}), but got ${value.quoted()}."
            )
        }
    }

    private fun MutableList<String>.validateJavaPackageName(property: KProperty0<String?>, example: String) {
        val value = property.get() ?: return
        if (!SourceVersion.isName(value)) {
            add(
                "Invalid '${property.name}': expected a valid Java package name " +
                    "(e.g. ${example.quoted()}), but got ${value.quoted()}."
            )
        }
    }

    private fun MutableList<String>.validateJavaClassName(property: KProperty0<String?>, example: String) {
        val value = property.get() ?: return
        if (value.isInvalidClassName()) {
            add(
                "Invalid '${property.name}': expected a valid fully-qualified name " +
                    "(e.g. ${example.quoted()}), but got ${value.quoted()}."
            )
        }
    }

    companion object {
        private const val MIXIN_ANNOTATION = "org.spongepowered.asm.mixin.Mixin"
        private const val UNIQUE_ANNOTATION = "org.spongepowered.asm.mixin.Unique"
        private const val SHADOW_ANNOTATION = "org.spongepowered.asm.mixin.Shadow"
        private const val MUTABLE_ANNOTATION = "org.spongepowered.asm.mixin.Mutable"
        private const val FINAL_ANNOTATION = "org.spongepowered.asm.mixin.Final"
    }
}

private fun String.isInvalidClassName(): Boolean = runCatching { JPClassName.bestGuess(this) }.isFailure
