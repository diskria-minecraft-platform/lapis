package io.github.diskria.lapis.phases.parser.models.schemas

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.annotations.AccessStrategy
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.common.JvmClassName
import io.github.diskria.lapis.phases.parser.models.common.SymbolSource

class ParsedSchema(
    val classDeclaration: KSClassDeclaration,
    val side: Side,
    val isTopLevel: Boolean,
    val hasPackageName: Boolean,
    val originClassDeclaration: KSClassDeclaration?,
    val originJvmClassName: JvmClassName?,
    val hasClassAnnotation: Boolean,
    val hasInnerClassAnnotation: Boolean,
    val hasLocalClassAnnotation: Boolean,
    val hasAnonymousClassAnnotation: Boolean,
    val hasAccessAnnotation: Boolean,
    val isAccessUnfinal: Boolean,
    val isAccessible: Boolean,
    val accessStrategy: AccessStrategy?,
    val descriptors: List<ParsedDescriptor>,
    val nestedSchemas: List<ParsedSchema>,
) : SymbolSource {
    override val symbol: KSNode = classDeclaration
}
