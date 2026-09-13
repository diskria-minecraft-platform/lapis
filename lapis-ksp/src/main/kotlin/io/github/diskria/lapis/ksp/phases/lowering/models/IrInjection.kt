package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class IrInjection(
    val jvmName: String,
    val extensionReceiverClassName: XClassName?,
    val mixinAnnotations: List<IrMixinAnnotation>,
    val isStatic: Boolean,
    val parameters: List<IrNativeInjectionParameter>,
    val returnTypeName: XTypeName?,
)

class IrNativeInjectionParameter(
    val name: String,
    val typeName: XTypeName,
    val mixinAnnotations: List<IrMixinAnnotation>,
)
