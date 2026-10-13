package io.github.diskria.lapis.kcp.fir.parser

import io.github.diskria.lapis.core.extensions.lapisLog
import io.github.diskria.lapis.core.parser.models.Node
import io.github.diskria.lapis.kcp.fir.validator.LapisDiagnostics
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn

class FirNode(
    private val source: KtSourceElement?,
    private val reporter: DiagnosticReporter,
    private val context: DiagnosticContext,
) : Node {
    override fun report(message: String) {
        if (source == null) {
            lapisLog("WARNING: Cannot report diagnostic '$message' because source is NULL!")
            return
        }
        reporter.reportOn(source, LapisDiagnostics.FACTORY, message, context)
    }
}
