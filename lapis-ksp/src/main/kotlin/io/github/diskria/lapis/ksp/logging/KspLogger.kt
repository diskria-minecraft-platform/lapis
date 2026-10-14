package io.github.diskria.lapis.ksp.logging

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.NonExistLocation
import io.github.diskria.lapis.core.logging.LapisLogger

class KspLogger(private val logger: KSPLogger) : LapisLogger {

    fun warn(message: String, node: KSNode) {
        logger.warn(buildFullMessage(message, node))
    }

    fun error(message: String, node: KSNode) {
        logger.error(buildFullMessage(message, node))
    }

    override fun warn(message: String) {
        logger.warn(buildFullMessage(message, null))
    }

    override fun error(message: String) {
        logger.error(buildFullMessage(message, null))
    }

    private fun buildFullMessage(message: String, node: KSNode?): String = buildString {
        appendLine("Lapis: ${message.trimEnd()}")
        node?.let {
            val locationText = when (val location = it.location) {
                is FileLocation -> location.ideaLink
                is NonExistLocation -> "<no physical location>"
            }
            appendLine("└── '$node' at $locationText")
        }
    }
}

private val FileLocation.ideaLink: String
    get() = "file://$filePath:$lineNumber"
