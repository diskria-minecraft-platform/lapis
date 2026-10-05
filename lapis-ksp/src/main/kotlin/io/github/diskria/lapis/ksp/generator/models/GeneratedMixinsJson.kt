package io.github.diskria.lapis.ksp.generator.models

import io.github.diskria.lapis.annotations.Side
import io.github.diskria.poetesse.interop.XClassName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratedMixinsJson(
    val mixins: List<String>? = null,
    val client: List<String>? = null,
    val server: List<String>? = null,
) {
    companion object {
        fun of(mixinPackage: String, classNames: Map<Side, List<XClassName>>): GeneratedMixinsJson =
            GeneratedMixinsJson(
                mixins = classNames.getRelativeNames(Side.Common, mixinPackage),
                client = classNames.getRelativeNames(Side.Client, mixinPackage),
                server = classNames.getRelativeNames(Side.Server, mixinPackage),
            )

        private fun Map<Side, List<XClassName>>.getRelativeNames(side: Side, mixinPackage: String): List<String>? =
            get(side)?.takeIf { it.isNotEmpty() }?.map { it.qualifiedName.removePrefix("$mixinPackage.") }
    }
}
