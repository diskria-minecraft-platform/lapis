package io.github.diskria.lapis.phases.parser.models.common

import com.google.devtools.ksp.symbol.KSNode

interface SymbolSource {
    val symbol: KSNode
}
