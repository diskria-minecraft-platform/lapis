package io.github.diskria.lapis.kcp

import io.github.diskria.lapis.core.LapisLogger
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

class KcpLogger(private val messageCollector: MessageCollector) : LapisLogger<CompilerMessageSourceLocation> {

    override fun warn(message: String, node: CompilerMessageSourceLocation?) {
        messageCollector.report(CompilerMessageSeverity.WARNING, message)
    }

    override fun error(message: String, node: CompilerMessageSourceLocation?) {
        messageCollector.report(CompilerMessageSeverity.ERROR, message)
    }
}
