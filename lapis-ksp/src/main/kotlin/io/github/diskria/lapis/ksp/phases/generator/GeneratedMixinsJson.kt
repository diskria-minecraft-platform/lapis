package io.github.diskria.lapis.ksp.phases.generator

import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratedMixinsJson(
    val mixins: List<String>? = null,
    val client: List<String>? = null,
    val server: List<String>? = null,
) {
    companion object {
        fun of(basePackage: String, qualifiedNames: Map<Side, List<IrClassName>>): GeneratedMixinsJson =
            GeneratedMixinsJson(
                mixins = qualifiedNames.getRelativeNames(Side.Common, basePackage),
                client = qualifiedNames.getRelativeNames(Side.ClientOnly, basePackage),
                server = qualifiedNames.getRelativeNames(Side.ServerOnly, basePackage),
            )

        private fun Map<Side, List<IrClassName>>.getRelativeNames(side: Side, basePackage: String): List<String>? =
            get(side)?.takeIf { it.isNotEmpty() }?.map { it.qualifiedName.removePrefix("$basePackage.") }
    }
}
