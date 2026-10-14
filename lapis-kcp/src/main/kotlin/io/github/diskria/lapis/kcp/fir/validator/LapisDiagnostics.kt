package io.github.diskria.lapis.kcp.fir.validator

import io.github.diskria.lapis.core.extensions.lapisError
import io.github.diskria.lapis.core.extensions.simpleNameOf
import io.github.diskria.lapis.core.validator.Diagnostic
import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.psi.KtElement

object LapisDiagnostics : KtDiagnosticsContainer() {

    private data class FactoryKey(val targetStrategy: Diagnostic.TargetStrategy, val isWarning: Boolean)

    private val factories: Map<FactoryKey, KtDiagnosticFactory1<String>> =
        Diagnostic.TargetStrategy.entries.flatMap { target ->
            val strategy = when (target) {
                Diagnostic.TargetStrategy.VISIBILITY_MODIFIER -> SourceElementPositioningStrategies.VISIBILITY_MODIFIER
                Diagnostic.TargetStrategy.RETURN_TYPE -> SourceElementPositioningStrategies.DECLARATION_RETURN_TYPE
                Diagnostic.TargetStrategy.NAME -> SourceElementPositioningStrategies.DECLARATION_NAME_ONLY
                Diagnostic.TargetStrategy.RECEIVER_TYPE -> SourceElementPositioningStrategies.DECLARATION_RECEIVER_TYPE
                Diagnostic.TargetStrategy.ELEMENT -> SourceElementPositioningStrategies.WHOLE_ELEMENT
            }
            val prefix = "LAPIS_${target.name}_"
            fun createFor(severity: Severity) = KtDiagnosticFactory1<String>(
                name = "$prefix${severity.name}",
                severity = severity,
                defaultPositioningStrategy = strategy,
                psiType = KtElement::class,
                rendererFactory = getRendererFactory(),
            )
            listOf(
                FactoryKey(target, isWarning = false) to createFor(Severity.ERROR),
                FactoryKey(target, isWarning = true) to createFor(Severity.WARNING),
            )
        }.toMap()

    fun factoryFor(diagnostic: Diagnostic): KtDiagnosticFactory1<String> =
        factories[FactoryKey(diagnostic.targetStrategy, diagnostic.isWarning)]
            ?: lapisError("Factory for ${diagnostic.targetStrategy} (isWarn=${diagnostic.isWarning}) is not initialized!")

    override fun getRendererFactory() = RendererFactory

    object RendererFactory : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap(simpleNameOf<LapisDiagnostics>()) { map ->
            factories.values.forEach { factory -> map.put(factory, "{0}", CommonRenderers.STRING) }
        }
    }
}

@Suppress("UnusedReceiverParameter")
private val SourceElementPositioningStrategies.DECLARATION_RECEIVER_TYPE
    get() = SourceElementPositioningStrategy(
        LightTreePositioningStrategies.DEFAULT,
        PositioningStrategies.RECEIVER
    )
