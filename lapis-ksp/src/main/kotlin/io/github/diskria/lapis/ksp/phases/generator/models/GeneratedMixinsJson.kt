package io.github.diskria.lapis.ksp.phases.generator.models

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
        fun of(basePackage: String, classNames: Map<Side, List<XClassName>>): GeneratedMixinsJson =
            GeneratedMixinsJson(
                mixins = classNames.getRelativeNames(Side.Common, basePackage),
                client = classNames.getRelativeNames(Side.Client, basePackage),
                server = classNames.getRelativeNames(Side.Server, basePackage),
            )

        private fun Map<Side, List<XClassName>>.getRelativeNames(
            side: Side,
            basePackage: String,
        ) = get(side)?.takeIf { it.isNotEmpty() }?.map { it.qualifiedName.removePrefix("$basePackage.") }
    }
}
