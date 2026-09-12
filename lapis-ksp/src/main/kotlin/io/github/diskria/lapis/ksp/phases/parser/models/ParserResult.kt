package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import io.github.diskria.lapis.ksp.phases.parser.models.patches.ParsedPatch

class ParserResult(
    val patches: List<ParsedPatch>,
)

class ParserPrepareResult(
    val patchClassDeclarations: List<KSClassDeclaration>,
) {
    val deferredSymbols: List<KSAnnotated> get() = patchClassDeclarations
}
