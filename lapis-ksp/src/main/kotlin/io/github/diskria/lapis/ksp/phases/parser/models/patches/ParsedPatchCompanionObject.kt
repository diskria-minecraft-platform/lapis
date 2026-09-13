package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSNode

class ParsedPatchCompanionObject(
    override val symbol: KSNode,
    val isPublic: Boolean,
    val functions: List<ParsedPatchFunction>,
) : SymbolSource
