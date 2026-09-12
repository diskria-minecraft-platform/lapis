package io.github.diskria.lapis.ksp.phases.generator.builders

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.extensions.kp.buildKotlinCodeBlock
import io.github.diskria.poetesse.kotlin.KPCodeBlock
import io.github.diskria.poetesse.kotlin.KPFunctionBuilder

@JvmInline
value class GenKotlinFunctionBody(private val builder: KPFunctionBuilder) {

    fun GenKotlinFunctionBody.code_(codeBlock: KPCodeBlock) {
        builder.addStatement("%L", codeBlock)
    }

    fun GenKotlinFunctionBody.return_(
        format: String? = null,
        argumentsBuilder: Builder<IrKotlinCodeBlock.Arguments> = {}
    ) {
        code_(buildKotlinCodeBlock("return" + format?.let { " $it" }.orEmpty(), argumentsBuilder))
    }

    fun GenKotlinFunctionBody.code_(
        format: String,
        isReturn: Boolean = false,
        argumentsBuilder: Builder<IrKotlinCodeBlock.Arguments> = {}
    ) {
        if (isReturn) {
            return_(format, argumentsBuilder)
        } else {
            code_(buildKotlinCodeBlock(format, argumentsBuilder))
        }
    }
}
