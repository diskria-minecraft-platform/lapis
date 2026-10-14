package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.core.parser.models.Node
import io.github.diskria.lapis.core.validator.Diagnostic
import io.github.diskria.lapis.ksp.logging.KspLogger

class KspNode(private val ksNode: KSNode) : Node {

    fun reportDiagnostic(diagnostic: Diagnostic, logger: KspLogger) {
//        if (diagnostic.isWarning) {
//            logger.warn(diagnostic.message, ksNode)
//        } else {
//            logger.error(diagnostic.message, ksNode)
//        }
    }

    override fun toString(): String = ksNode.toString()
}
