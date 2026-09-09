package io.github.diskria.lapis.ksp.phases.lowering.models

sealed interface IrLocal
class IrNamedLocal(val name: String) : IrLocal
class IrPositionalLocal(val ordinal: Int) : IrLocal
