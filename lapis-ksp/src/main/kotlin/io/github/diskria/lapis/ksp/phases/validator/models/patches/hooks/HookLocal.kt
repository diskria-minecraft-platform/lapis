package io.github.diskria.lapis.ksp.phases.validator.models.patches.hooks

sealed interface HookLocal
class NamedLocal(val name: String) : HookLocal
class PositionalLocal(val ordinal: Int) : HookLocal
