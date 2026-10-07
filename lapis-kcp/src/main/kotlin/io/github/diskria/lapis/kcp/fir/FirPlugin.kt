package io.github.diskria.lapis.kcp.fir

import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.kcp.KcpLogger
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FirPlugin(private val logger: KcpLogger, private val options: CoreOptions) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {

    }
}
