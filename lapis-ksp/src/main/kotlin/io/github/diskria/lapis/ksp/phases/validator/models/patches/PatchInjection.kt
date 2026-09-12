package io.github.diskria.lapis.ksp.phases.validator.models.patches

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.validator.models.common.MixinAnnotation

sealed class PatchInjection {
    abstract val jvmName: String
    protected abstract val extensionReceiverClassDeclaration: KSClassDeclaration?
    abstract val isStatic: Boolean

    val extensionReceiverClassName: IrClassName?
        get() = extensionReceiverClassDeclaration?.asIrClassName()
}

class PatchNativeInjection(
    override val jvmName: String,
    override val extensionReceiverClassDeclaration: KSClassDeclaration?,
    val mixinAnnotations: List<MixinAnnotation>,
    override val isStatic: Boolean,
    val parameters: List<PatchNativeInjectionParameter>,
    val returnType: KSType?,
) : PatchInjection()

class PatchNativeInjectionParameter(
    val name: String,
    val type: KSType,
    val mixinAnnotations: List<MixinAnnotation>,
)
