package io.github.diskria.lapis.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.phases.parser.models.common.ParsedAnnotation
import io.github.diskria.lapis.phases.parser.models.common.SymbolSource

class ParsedPatch(
    val name: String?,
    val side: Side,
    val isClass: Boolean,
    val isObject: Boolean,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isTopLevel: Boolean,
    val hasPackageName: Boolean,
    val isPublic: Boolean,
    val initStrategy: InitStrategy?,
    val classDeclaration: KSClassDeclaration,
    val targetClassDeclaration: KSClassDeclaration?,

    val companionObjects: List<ParsedPatchCompanionObject>,
    val constructors: List<ParsedPatchConstructor>,
    val bodyProperties: List<ParsedPatchProperty>,
    val functions: List<ParsedPatchFunction>,

    val annotations: List<ParsedAnnotation>,
) : SymbolSource {
    override val symbol: KSNode = classDeclaration
}
