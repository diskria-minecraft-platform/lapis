package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.core.lowering.models.IrMixin.Injection.Parameter
import io.github.diskria.poetesse.interop.XClassName

class IrMixin<O>(
    val origin: O?,
    val className: XClassName,
    val side: Side,
    val injections: List<Injection>,
    val duck: IrMixinDuck<O>?,
    val annotations: List<IrAnnotation>,
) {
    sealed interface Injection {

        val name: String
        val sourceJvmName: String
        val annotations: List<IrAnnotation>
        val parameters: List<Parameter>
        val contextParameters: List<Parameter>
        val returnType: IrType?

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
        override val contextParameters: List<Parameter>,
        override val returnType: IrType?,
        val extensionReceiverType: IrType?
    ) : Injection

    class StaticInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val annotations: List<IrAnnotation>,
        override val parameters: List<Parameter>,
        override val contextParameters: List<Parameter>,
        override val returnType: IrType?,
        val kMixinCompanionObjectName: String,
    ) : Injection
}
