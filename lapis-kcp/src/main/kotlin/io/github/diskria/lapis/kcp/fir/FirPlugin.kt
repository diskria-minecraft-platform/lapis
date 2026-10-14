package io.github.diskria.lapis.kcp.fir

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.kcp.fir.generator.FirGenerator
import io.github.diskria.lapis.kcp.fir.parser.FirParser
import io.github.diskria.lapis.kcp.fir.validator.FirValidator
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class FirPlugin(private val options: CliOptions) : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +::FirParser
        +(::FirValidator.bind(options))
        +::FirGenerator
    }
}
