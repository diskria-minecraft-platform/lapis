package io.github.diskria.lapis.kcp.ir.transformers

import io.github.diskria.lapis.kcp.fir.generator.LapisParsedKey
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class IrExtensionCallTransformer(private val context: IrPluginContext) : IrElementTransformerVoid() {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun visitCall(expression: IrCall): IrExpression {
        return super.visitCall(expression)
//        val callee = expression.symbol.owner
//        val calleeGeneratedOrigin = callee.origin as? IrDeclarationOrigin.GeneratedByPlugin
//            ?: return super.visitCall(expression)
//        if (calleeGeneratedOrigin.pluginKey != LapisParsedKey) return super.visitCall(expression)
//        if (callee.name != METHOD_NAME) return super.visitCall(expression)
//        val calleePackageFragment = callee.parent as? IrPackageFragment ?: return super.visitCall(expression)
//        if (calleePackageFragment.packageFqName != TARGET_PACKAGE) return super.visitCall(expression)
//        val firMetadata = callee.metadata as? FirMetadataSource.Function
//
//        val textToPrint = firMetadata?.fir?.lapisExtensionData ?: return super.visitCall(expression)
//        val printlnCallable = CallableId(FqName("kotlin.io"), Name.identifier("println"))
//        val replaceWith = context.finderForBuiltins().findFunctions(printlnCallable).firstOrNull { symbol ->
//            val symbolOwner = symbol.owner
//            symbolOwner.parameters.size == 1 && symbolOwner.parameters.first().type == context.irBuiltIns.anyNType
//        } ?: return super.visitCall(expression)
//        val startOffset = expression.startOffset
//        val endOffset = expression.endOffset
//        val builtins = context.irBuiltIns
//        return IrCallImpl(
//            startOffset = startOffset,
//            endOffset = endOffset,
//            type = builtins.unitType,
//            symbol = replaceWith,
//            typeArgumentsCount = 0,
//        ).apply {
//            arguments[0] = IrConstImpl.string(
//                startOffset = startOffset,
//                endOffset = endOffset,
//                type = builtins.stringType,
//                value = textToPrint
//            )
//        }
    }
}
