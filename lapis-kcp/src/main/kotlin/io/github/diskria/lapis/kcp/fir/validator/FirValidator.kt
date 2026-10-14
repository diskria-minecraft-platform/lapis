package io.github.diskria.lapis.kcp.fir.validator

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.lapisError
import io.github.diskria.lapis.core.parser.models.KMixinNode
import io.github.diskria.lapis.core.validator.CoreValidator
import io.github.diskria.lapis.core.validator.DiagnosticReport
import io.github.diskria.lapis.core.validator.models.KMixinModel
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
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataKey
import org.jetbrains.kotlin.fir.declarations.FirDeclarationDataRegistry.data
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.extensionService
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

class FirValidator(session: FirSession, private val options: CliOptions) : FirAdditionalCheckersExtension(session) {

    private val coreValidator = CoreValidator<Nothing>(options)

    @OptIn(SymbolInternals::class)
    fun validate(nodes: List<Pair<FirRegularClassSymbol, KMixinNode<Nothing>>>): List<KMixinModel<Nothing>> =
        nodes.mapNotNull { (symbol, node) ->
            val (model, diagnosticReports) = coreValidator.validate(node)
            symbol.fir.kMixinModel = model
            symbol.fir.kMixinDiagnosticReports = diagnosticReports
            model
        }

    private class Checker(private val options: CliOptions) : FirRegularClassChecker(MppCheckerKind.Common) {

        private val coreValidator = CoreValidator<Nothing>(options)

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            val nodes = context.session.parser.parse(listOf(declaration.symbol))
            nodes.forEach { node ->
                val (model, diagnosticReports) = coreValidator.validate(node)
                diagnosticReports.forEach { report ->
                    val targetNode = report.target as FirNode
                    targetNode.reportDiagnostic(report.diagnostic, context, reporter)
                }
//                try {
//                val model = declaration.kMixinModel ?: return

//                val generatedSymbols = context.session.symbolProvider.getTopLevelFunctionSymbols(
//                    packageFqName = TARGET_PACKAGE,
//                    name = METHOD_NAME
//                )
//                generatedSymbols.forEach { functionSymbol ->
//                    if (functionSymbol.origin == FirDeclarationOrigin.Plugin(LapisParsedKey)) {
//                        lapisLog("--- MyPlugin: Successfully attached lowering metadata to hardcoded boom!")
//                    }
//                }
//            } catch (t: Throwable) {
//                lapisLog("    CRASH IN VALIDATOR:\n${t.stackTraceToString()}")
//            }
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

object KMixinModelKey : FirDeclarationDataKey()
object KMixinDiagnosticReportsKey : FirDeclarationDataKey()

var FirDeclaration.kMixinModel: KMixinModel<Nothing>? by data(KMixinModelKey)
var FirDeclaration.kMixinDiagnosticReports: List<DiagnosticReport>? by data(KMixinDiagnosticReportsKey)
