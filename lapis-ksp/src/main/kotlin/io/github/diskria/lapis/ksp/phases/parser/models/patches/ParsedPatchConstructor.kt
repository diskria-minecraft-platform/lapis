package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.parser.models.common.SymbolSource

class ParsedPatchConstructor(
    override val symbol: KSNode,

    val isPublic: Boolean,
    val parameters: List<ParsedPatchConstructorParameter>,
) : SymbolSource

class ParsedPatchConstructorParameter(
    override val symbol: KSNode,

    val type: KSType?,
    val hasOriginAnnotation: Boolean,
) : SymbolSource
