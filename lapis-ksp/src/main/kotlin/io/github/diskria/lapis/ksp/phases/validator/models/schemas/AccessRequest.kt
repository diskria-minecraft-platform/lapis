package io.github.diskria.lapis.ksp.phases.validator.models.schemas

import io.github.diskria.lapis.annotations.Op
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter

sealed interface AccessRequest
class TweakAccessRequest(val shouldRemoveFinal: Boolean) : AccessRequest

sealed interface MixinAccessRequest : AccessRequest
class MixinFieldAccessRequest(
    val shouldRemoveFinal: Boolean,
    val ops: List<Op>,
) : MixinAccessRequest

class MixinInvokableAccessRequest(
    val parameters: List<IrParameter>,
) : MixinAccessRequest
