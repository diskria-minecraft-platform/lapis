package io.github.diskria.lapis.kcp.fir

import io.github.diskria.lapis.core.CoreOptions
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FirPlugin(private val options: CoreOptions) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {

    }
}
