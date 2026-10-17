package io.github.diskria.lapis.kcp.ir.transformers

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

class IrExtensionCallTransformer(private val context: IrPluginContext) : IrElementTransformerVoid() {

    override fun visitCall(expression: IrCall): IrExpression {

        return super.visitCall(expression)
    }
}
