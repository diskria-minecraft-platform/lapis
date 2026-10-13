package io.github.diskria.lapis.kcp.fir.validator

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.lapisLog
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.kcp.fir.parser.FirParser
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

class FirValidator(session: FirSession, private val options: CliOptions) : FirAdditionalCheckersExtension(session) {

    private class Checker(private val options: CliOptions) : FirRegularClassChecker(MppCheckerKind.Common) {

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            lapisLog(">>> VALIDATOR CALLED FOR: ${declaration.name.asString()}")
            try {
                val node = FirParser(context, reporter).parseNode(declaration) ?: return
                lapisLog("    Node parsed: ${node.name}")
                val models = CoreValidator(sequenceOf(node), options).validate().toList().ifEmpty { return }
                lapisLog("    Validation finished, models count: ${models.size}")
            } catch (t: Throwable) {
                lapisLog("    CRASH IN VALIDATOR:\n${t.stackTraceToString()}")
            }
        }
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val regularClassCheckers: Set<FirRegularClassChecker> get() = setOf(FirValidator.Checker(options))
    }
}
