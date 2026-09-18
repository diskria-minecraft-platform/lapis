package io.github.diskria.lapis.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.NonExistLocation

class KspLogger(private val logger: KSPLogger) {

    private var currentPhase: Phase = Phase.BOOTSTRAP

    fun info(message: String, symbol: KSNode? = null) {
        logger.info(buildFullMessage(message, symbol))
    }

    fun warn(message: String, symbol: KSNode? = null) {
        logger.warn(buildFullMessage(message, symbol))
    }

    fun error(message: String, symbol: KSNode? = null) {
        logger.error(buildFullMessage(message, symbol))
    }

    fun fatal(message: String, symbol: KSNode? = null): Nothing {
        error(message, symbol)
        throw LapisException(message)
    }

    fun setPhase(phase: Phase) {
        currentPhase = phase
    }

    private fun buildFullMessage(message: String, symbol: KSNode?): String = buildString {
        appendLine("[Lapis] [Phase: $currentPhase]")
        appendLine(message.trimEnd())
        symbol?.let {
            val locationText = when (val location = it.location) {
                is FileLocation -> location.ideaLink
                is NonExistLocation -> "<no physical location>"
            }
            appendLine("├── symbol: $symbol")
            appendLine("└── at $locationText")
        }
    }

    enum class Phase {
        BOOTSTRAP,
        PARSING,
        VALIDATION,
        TRANSFORMATION,
        GENERATION,
    }
}

private val FileLocation.ideaLink: String
    get() = "file://$filePath:$lineNumber"
