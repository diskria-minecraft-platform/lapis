package io.github.diskria.lapis.phases.lowering.models

sealed interface IrLocal
class IrNamedLocal(val name: String) : IrLocal
class IrPositionalLocal(val ordinal: Int) : IrLocal
