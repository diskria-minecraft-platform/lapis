package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType

class ParsedPatchFunctionParameter(
    override val symbol: KSNode,
    val name: String?,
    val type: KSType?,
    val annotations: List<ParsedAnnotation>,
) : SymbolSource
