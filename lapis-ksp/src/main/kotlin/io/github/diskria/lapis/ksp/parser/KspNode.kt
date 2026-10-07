package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.core.parser.models.Node
import io.github.diskria.lapis.ksp.KspLogger

class KspNode(val ksNode: KSNode, private val logger: KspLogger) : Node {

    override fun report(message: String) {
        logger.error(message, this)
    }

    override fun toString(): String = ksNode.toString()
}
