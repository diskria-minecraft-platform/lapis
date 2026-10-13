package io.github.diskria.lapis.kcp.fir.validator

import io.github.diskria.lapis.core.extensions.simpleNameOf
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.psi.KtElement

object LapisDiagnostics : KtDiagnosticsContainer() {

    val FACTORY by error1<KtElement, String>(SourceElementPositioningStrategies.DECLARATION_NAME)
    override fun getRendererFactory() = RendererFactory

    object RendererFactory : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap(simpleNameOf<LapisDiagnostics>()) {
            it.put(FACTORY, "{0}", CommonRenderers.STRING)
        }
    }
}
