package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.NonExistLocation

class KspLogger(private val logger: KSPLogger) {

    fun warn(message: String, node: KSNode? = null) {
        logger.warn(buildFullMessage(message, node))
    }

    fun error(message: String, node: KSNode? = null) {
        logger.error(buildFullMessage(message, node))
    }

    fun fatal(message: String, node: KSNode? = null): Nothing {
        error(message, node)
        throw LapisException(message)
    }

    private fun buildFullMessage(message: String, node: KSNode?): String = buildString {
        appendLine("[Lapis]")
        appendLine(message.trimEnd())
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
