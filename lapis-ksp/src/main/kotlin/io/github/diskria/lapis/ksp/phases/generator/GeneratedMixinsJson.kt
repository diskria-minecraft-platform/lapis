package io.github.diskria.lapis.ksp.phases.generator

import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratedMixinsJson(
    val mixins: List<String>? = null,
    val client: List<String>? = null,
    val server: List<String>? = null,
) {
    companion object {
        fun of(basePackage: String, qualifiedNames: Map<Env, List<IrClassName>>): GeneratedMixinsJson =
            GeneratedMixinsJson(
                mixins = qualifiedNames.getRelativeNames(Env.Common, basePackage),
                client = qualifiedNames.getRelativeNames(Env.Client, basePackage),
                server = qualifiedNames.getRelativeNames(Env.Server, basePackage),
            )

        private fun Map<Env, List<IrClassName>>.getRelativeNames(env: Env, basePackage: String): List<String>? =
            get(env)?.takeIf { it.isNotEmpty() }?.map { it.qualifiedName.removePrefix("$basePackage.") }
    }
}
