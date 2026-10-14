package io.github.diskria.lapis.kcp.logging

import io.github.diskria.lapis.core.logging.LapisLogger
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector

class KcpLogger(private val messageCollector: MessageCollector) : LapisLogger {

    override fun warn(message: String) {
        messageCollector.report(CompilerMessageSeverity.WARNING, message)
    }

    override fun error(message: String) {
        messageCollector.report(CompilerMessageSeverity.ERROR, message)
    }
}
