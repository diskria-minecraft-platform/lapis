package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName

sealed interface IrInjection : IrReturnable {
    val jvmName: String
    val isStatic: Boolean
    override val returnTypeName: IrTypeName?
}

class IrNativeInjection(
    override val jvmName: String,
    val extensionReceiverClassName: IrClassName?,
    val mixinAnnotations: List<IrMixinAnnotation>,
    override val isStatic: Boolean,
    val parameters: List<IrNativeInjectionParameter>,
    override val returnTypeName: IrTypeName?
) : IrInjection

class IrNativeInjectionParameter(
    val name: String,
    val typeName: IrTypeName,
    val mixinAnnotations: List<IrMixinAnnotation>,
)
