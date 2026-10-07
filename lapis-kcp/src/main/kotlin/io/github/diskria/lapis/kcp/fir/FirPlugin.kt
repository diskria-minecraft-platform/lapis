package io.github.diskria.lapis.kcp.fir

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.kcp.logging.KcpLogger
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FirPlugin(private val logger: KcpLogger, private val options: CliOptions) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {

    }
}
