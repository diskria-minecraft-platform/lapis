package io.github.diskria.lapis.kcp.fir.parser

import io.github.diskria.lapis.core.parser.models.Node
import io.github.diskria.lapis.core.validator.Diagnostic
import io.github.diskria.lapis.kcp.fir.validator.LapisDiagnostics
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn

class FirNode(private val source: KtSourceElement?) : Node {

    fun reportDiagnostic(diagnostic: Diagnostic, context: DiagnosticContext, reporter: DiagnosticReporter) {
        if (source == null) {
            return
        }
        reporter.reportOn(source, LapisDiagnostics.factoryFor(diagnostic), diagnostic.message, context)
    }
}
