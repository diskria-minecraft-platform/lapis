package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.phases.lowering.models.IrMixin.Injection.Parameter
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName

class IrMixin(
    val originatingFile: KSFile?,
    val className: XClassName,
    val typeVariables: List<XTypeVariableName>,
    val side: Side,
    val injections: List<Injection>,
    val duck: IrMixinDuck?,
    val annotations: List<IrAnnotation>,
) {
    sealed interface Injection {

        val name: String
        val sourceJvmName: String
        val annotations: List<IrAnnotation>
        val parameters: List<Parameter>
        val returnType: IrType?
        val typeVariables: List<XTypeVariableName>

        class Parameter(
            val name: String,
            val type: IrType,
            val annotations: List<IrAnnotation>,
        )
    }

    class MemberInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val annotations: List<IrAnnotation>,
        override val parameters: List<Parameter>,
        override val returnType: IrType?,
        override val typeVariables: List<XTypeVariableName>,
        val extensionReceiverTargetTypeCast: IrType?
    ) : Injection

    class StaticInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val annotations: List<IrAnnotation>,
        override val parameters: List<Parameter>,
        override val returnType: IrType?,
        override val typeVariables: List<XTypeVariableName>,
        val kMixinCompanionObjectName: String,
    ) : Injection
}
