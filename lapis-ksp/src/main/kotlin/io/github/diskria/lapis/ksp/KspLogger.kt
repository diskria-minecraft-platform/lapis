package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.NonExistLocation
import io.github.diskria.lapis.core.LapisLogger
import io.github.diskria.lapis.ksp.parser.KspNode

class KspLogger(private val logger: KSPLogger) : LapisLogger<KspNode> {

    override fun warn(message: String, node: KspNode?) {
        logger.warn(buildFullMessage(message, node))
    }

    override fun error(message: String, node: KspNode?) {
        logger.error(buildFullMessage(message, node))
    }

    private fun buildFullMessage(message: String, node: KspNode?): String = buildString {
        appendLine("Lapis: ${message.trimEnd()}")
        node?.let {
            val locationText = when (val location = it.ksNode.location) {
                is FileLocation -> location.ideaLink
                is NonExistLocation -> "<no physical location>"
            }
            appendLine("└── '$node' at $locationText")
        }
    }
}

private val FileLocation.ideaLink: String
    get() = "file://$filePath:$lineNumber"
