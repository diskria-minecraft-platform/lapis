package io.github.diskria.lapis.ksp

import io.github.diskria.lapis.ksp.extensions.quoted
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

    @Serializable(with = DelimitedStringListSerializer::class)
    val mixinAnnotationPackages: List<String> = MIXIN_ANNOTATION_PACKAGES,
) {
    fun validate() = buildList {
        validateJavaIdentifierName(::uniqueModPrefix, "modid$")
        validateJavaPackageName(::mixinPackage, "com.example.modid.mixin")
        validateJavaPackageName(::mixinGeneratedSubpackage, "generated")
        validateJavaFQCN(::nullableAnnotation, "org.jspecify.annotations.Nullable")
        validateJavaFQCN(::nonNullAnnotation, "org.jspecify.annotations.NonNull")
        validateJavaFQCN(::mixinAnnotation, MIXIN_ANNOTATION)
        validateJavaFQCN(::uniqueAnnotation, UNIQUE_ANNOTATION)
        validateJavaFQCN(::shadowAnnotation, SHADOW_ANNOTATION)
        validateJavaFQCN(::mutableAnnotation, MUTABLE_ANNOTATION)
        validateJavaFQCN(::finalAnnotation, FINAL_ANNOTATION)
        validateJavaPackageNames(::mixinAnnotationPackages, SPONGE_ANNOTATIONS_PACKAGE)
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

    private fun MutableList<String>.validateJavaPackageNames(property: KProperty0<List<String>?>, example: String) {
        val values = property.get() ?: return
        values.forEachIndexed { index, value ->
            if (!SourceVersion.isName(value)) {
                add(
                    "Invalid element at index $index in '${property.name}': expected a valid Java package name " +
                        "(e.g. ${example.quoted()}), but got ${value.quoted()}."
                )
            }
        }
    }

    private fun MutableList<String>.validateJavaFQCN(property: KProperty0<String?>, example: String) {
        val value = property.get() ?: return
        if (!value.isValidClassName()) {
            add(
                "Invalid '${property.name}': expected a valid FQCN " +
                    "(e.g. ${example.quoted()}), but got ${value.quoted()}."
            )
        }
    }

    companion object {
        private const val SPONGE_ANNOTATIONS_PACKAGE = "org.spongepowered.asm.mixin"

        private const val MIXIN_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Mixin"
        private const val UNIQUE_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Unique"
        private const val SHADOW_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Shadow"
        private const val MUTABLE_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Mutable"
        private const val FINAL_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Final"

        private val MIXIN_ANNOTATION_PACKAGES = listOf(SPONGE_ANNOTATIONS_PACKAGE, "com.llamalad7.mixinextras")
    }
}

private fun String.isValidClassName(): Boolean {
    if (!SourceVersion.isName(this)) return false
    val dotIndex = lastIndexOf('.').takeIf { it > 0 } ?: return false
    return getOrNull(dotIndex + 1)?.isUpperCase() == true
}

object DelimitedStringListSerializer : KSerializer<List<String>> {

    private val DELIMITERS_REGEX = Regex("[,;:|]+")

    override val descriptor = PrimitiveSerialDescriptor("DelimitedStringListSerializer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> =
        decoder.decodeString().split(DELIMITERS_REGEX)

    override fun serialize(encoder: Encoder, value: List<String>) =
        error("Serialization is not supported for KspOptions")
}
