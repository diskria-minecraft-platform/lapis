package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSNode

interface SymbolSource {
    val symbol: KSNode
}
