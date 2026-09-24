package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.phases.lowering.models.IrMixin.Injection.Parameter
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.interop.XTypeVariableName

class IrMixin(
    val patchOriginatingFile: KSFile?,
    val className: XClassName,
    val typeVariables: List<XTypeVariableName>,
    val side: Side,
    val injections: List<Injection>,
    val duck: IrMixinDuck?,
    val annotations: List<IrMixinAnnotation>,
) {
    sealed interface Injection {

        val name: String
        val sourceJvmName: String
        val mixinAnnotations: List<IrMixinAnnotation>
        val parameters: List<Parameter>
        val returnTypeName: XTypeName?
        val typeVariables: List<XTypeVariableName>

        class Parameter(
            val name: String,
            val typeName: XTypeName,
            val mixinAnnotations: List<IrMixinAnnotation>,
        )
    }

    class MemberInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val mixinAnnotations: List<IrMixinAnnotation>,
        override val parameters: List<Parameter>,
        override val returnTypeName: XTypeName?,
        override val typeVariables: List<XTypeVariableName>,
        val extensionReceiverTargetTypeCast: IrTargetSubtypeCast?
    ) : Injection

    class StaticInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val mixinAnnotations: List<IrMixinAnnotation>,
        override val parameters: List<Parameter>,
        override val returnTypeName: XTypeName?,
        override val typeVariables: List<XTypeVariableName>,
        val patchCompanionObjectName: String,
    ) : Injection
}
