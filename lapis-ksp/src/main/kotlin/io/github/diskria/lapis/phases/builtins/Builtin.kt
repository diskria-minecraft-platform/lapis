package io.github.diskria.lapis.phases.builtins

import io.github.diskria.lapis.phases.lowering.types.IrClassName

typealias BuiltinResolver = (Builtin<*>) -> IrClassName

sealed interface Builtin<T> {

    val name: String
    val isInternal: Boolean

    fun generate(resolveBuiltin: BuiltinResolver): T

    companion object {
        val entries: List<Builtin<*>> by lazy {
            SimpleBuiltin.entries + DescriptorWrapperBuiltin.entries + LocalVarImplBuiltin.entries
        }
    }
}
