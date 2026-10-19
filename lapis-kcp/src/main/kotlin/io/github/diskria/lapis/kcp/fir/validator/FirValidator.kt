package io.github.diskria.lapis.kcp.fir.validator

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.lapisError
import io.github.diskria.lapis.core.extensions.lapisFatal
import io.github.diskria.lapis.core.parser.models.KMixinNode
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.kcp.fir.parser.FirNode
import io.github.diskria.lapis.kcp.fir.parser.parser
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.analysis.extensions.additionalCheckers
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.extensionService

class FirValidator(session: FirSession, private val options: CliOptions) : FirAdditionalCheckersExtension(session) {

    private val coreValidator: CoreValidator<Nothing> = CoreValidator(options)

    fun validate(node: KMixinNode<Nothing>): CoreValidator.Result<Nothing> = coreValidator.validate(node)

    private class Checker(options: CliOptions) : FirRegularClassChecker(MppCheckerKind.Common) {

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            try {
                val node = context.session.parser.parse(declaration.symbol) ?: return
                val (model, diagnosticReports) = context.session.validator.validate(node)
                diagnosticReports.forEach { report ->
                    val targetNode = report.target as FirNode
                    targetNode.reportDiagnostic(report.diagnostic, context, reporter)
                }
                if (model == null) return
            } catch (t: Throwable) {
                lapisFatal("[FIR-VALIDATOR]", t)
            }
        }
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirRegularClassChecker> get() = setOf(FirValidator.Checker(options))
    }
}

val FirSession.validator: FirValidator
    get() = extensionService.additionalCheckers.firstNotNullOfOrNull { it as? FirValidator }
        ?: lapisError("FirValidator is not registered in FirPlugin.")
