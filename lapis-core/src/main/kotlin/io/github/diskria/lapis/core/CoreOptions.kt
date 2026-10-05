package io.github.diskria.lapis.core

import io.github.diskria.lapis.core.extensions.elements
import io.github.diskria.lapis.core.extensions.internalError
import io.github.diskria.lapis.core.extensions.quoted
import io.github.diskria.lapis.core.extensions.simpleNameOf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.serialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import javax.lang.model.SourceVersion
import kotlin.reflect.KProperty0

@Serializable
data class CoreOptions(
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
        if (!value.isValidJavaFQCN()) {
            add(
                "Invalid '${property.name}': expected a valid FQCN " +
                    "(e.g. ${example.quoted()}), but got ${value.quoted()}."
            )
        }
    }

    private fun String.isValidJavaFQCN(): Boolean {
        if (!SourceVersion.isName(this)) return false
        val dotIndex = lastIndexOf('.').takeIf { it > 0 } ?: return false
        return getOrNull(dotIndex + 1)?.isUpperCase() == true
    }

    class OptionSpec(
        val name: String,
        val isRequired: Boolean,
        val description: String = "Option '$name'"
    )

    companion object {
        private const val SPONGE_ANNOTATIONS_PACKAGE = "org.spongepowered.asm.mixin"

        private const val MIXIN_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Mixin"
        private const val UNIQUE_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Unique"
        private const val SHADOW_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Shadow"
        private const val MUTABLE_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Mutable"
        private const val FINAL_ANNOTATION = "$SPONGE_ANNOTATIONS_PACKAGE.Final"

        private val MIXIN_ANNOTATION_PACKAGES = listOf(SPONGE_ANNOTATIONS_PACKAGE, "com.llamalad7.mixinextras")

        private val optionsJson: Json = Json { ignoreUnknownKeys = true }

        private const val ARGUMENT_PREFIX: String = "lapis."
        private fun String.hasArgumentPrefix(): Boolean = startsWith(ARGUMENT_PREFIX)
        private fun String.withArgumentPrefix(): String = ARGUMENT_PREFIX + this
        private fun String.removeArgumentPrefix(): String = removePrefix(ARGUMENT_PREFIX)

        val specs = serialDescriptor<CoreOptions>().elements.map {
            OptionSpec(
                name = it.name.withArgumentPrefix(),
                isRequired = !it.isOptional,
            )
        }

        fun fromArguments(
            rawArguments: Map<String, String>,
            onWarn: (message: String) -> Unit,
            onError: (message: String) -> Nothing,
        ): CoreOptions {
            val scopedArguments = rawArguments.filterKeys { it.hasArgumentPrefix() }
            val existingKeys = specs.map { it.name }.toSet()
            val unknownKeys = scopedArguments.keys - existingKeys
            if (unknownKeys.isNotEmpty()) {
                onWarn("Unknown arguments: ${unknownKeys.joinToString { "'$it'" }}.")
            }
            val requiredKeys = specs.filter { it.isRequired }.map { it.name }.toSet()
            val missingRequiredKeys = requiredKeys - scopedArguments.keys
            if (missingRequiredKeys.isNotEmpty()) {
                onError("Missing required arguments: ${missingRequiredKeys.joinToString { "'$it'" }}.")
            }
            val options = runCatching {
                optionsJson.decodeFromJsonElement<CoreOptions>(buildJsonObject {
                    scopedArguments.forEach { (key, value) ->
                        put(key.removeArgumentPrefix(), JsonPrimitive(value))
                    }
                })
            }.getOrElse { error ->
                val message = error.message?.let { "\n$it" } ?: " failed to deserialize CoreOptions."
                internalError("Failed to parse Gradle arguments:$message")
            }
            val validationErrors = options.validate()
            if (validationErrors.isNotEmpty()) {
                onError("Invalid CoreOptions configuration:\n${validationErrors.joinToString("\n")}")
            }
            return options
        }
    }
}

object DelimitedStringListSerializer : KSerializer<List<String>> {

    private val DELIMITERS_REGEX = Regex("[,;:|]+")

    override val descriptor = PrimitiveSerialDescriptor(
        simpleNameOf<DelimitedStringListSerializer>(),
        PrimitiveKind.STRING
    )

    override fun deserialize(decoder: Decoder): List<String> = decoder.decodeString().split(DELIMITERS_REGEX)

    override fun serialize(encoder: Encoder, value: List<String>) =
        throw UnsupportedOperationException("Serialization is not supported for CoreOptions")
}
