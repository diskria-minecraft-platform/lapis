package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.poetesse.interop.XClassName

class PatchInjection(
    val jvmName: String,
    val extensionReceiverClassDeclaration: KSClassDeclaration?,
    val mixinAnnotations: List<MixinAnnotation>,
    val isStatic: Boolean,
    val parameters: List<PatchNativeInjectionParameter>,
    val returnType: KSType?,
) {
    val extensionReceiverClassName: XClassName? get() = extensionReceiverClassDeclaration?.toXClassName()
}

class PatchNativeInjectionParameter(
    val name: String,
    val type: KSType,
    val mixinAnnotations: List<MixinAnnotation>,
)
