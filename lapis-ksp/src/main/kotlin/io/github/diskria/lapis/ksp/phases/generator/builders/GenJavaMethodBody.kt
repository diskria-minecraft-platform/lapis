package io.github.diskria.lapis.ksp.phases.generator.builders

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.extensions.jp.buildJavaCodeBlock
import io.github.diskria.poetesse.java.JPCodeBlock
import io.github.diskria.poetesse.java.JPMethodBuilder

@JvmInline
value class GenJavaMethodBody(private val builder: JPMethodBuilder) {

    fun GenJavaMethodBody.code_(codeBlock: JPCodeBlock) {
        builder.addStatement(codeBlock)
    }

    fun GenJavaMethodBody.return_(
        format: String? = null,
        argumentsBuilder: Builder<IrJavaCodeBlock.Arguments> = {}
    ) {
        code_(buildJavaCodeBlock("return" + format?.let { " $it" }.orEmpty(), argumentsBuilder))
    }

    fun GenJavaMethodBody.code_(
        format: String,
        isReturn: Boolean = false,
        argumentsBuilder: Builder<IrJavaCodeBlock.Arguments> = {}
    ) {
        if (isReturn) {
            return_(format, argumentsBuilder)
        } else {
            code_(buildJavaCodeBlock(format, argumentsBuilder))
        }
    }

    fun GenJavaMethodBody.return_(codeBlock: JPCodeBlock) {
        code_(buildJavaCodeBlock("return %L") { +codeBlock })
    }

    fun GenJavaMethodBody.if_(condition: JPCodeBlock, body: Builder<IrJavaCodeBlock>) {
        withControlFlow(buildJavaCodeBlock("if (%L)") { +condition }, body)
    }

    fun GenJavaMethodBody.throw_(
        format: String,
        argumentsBuilder: Builder<IrJavaCodeBlock.Arguments> = {}
    ) {
        code_(buildJavaCodeBlock("throw $format", argumentsBuilder))
    }

    fun GenJavaMethodBody.synchronized_(lock: JPCodeBlock, body: Builder<IrJavaCodeBlock>) {
        builder.beginControlFlow(buildJavaCodeBlock("synchronized (%L)") { +lock })
        buildJavaCodeBlock(body)
        builder.endControlFlow()
    }

    private fun GenJavaMethodBody.withControlFlow(controlFlow: JPCodeBlock, body: Builder<IrJavaCodeBlock>) {
        builder.beginControlFlow(controlFlow)
        buildJavaCodeBlock(body)
        builder.endControlFlow()
    }
}
