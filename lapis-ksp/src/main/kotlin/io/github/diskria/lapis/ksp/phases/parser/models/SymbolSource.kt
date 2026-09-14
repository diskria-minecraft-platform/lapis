package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSNode

interface SymbolSource {
    val symbol: KSNode
}
