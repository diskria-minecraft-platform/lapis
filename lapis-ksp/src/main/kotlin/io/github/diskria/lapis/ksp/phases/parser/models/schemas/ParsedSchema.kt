package io.github.diskria.lapis.ksp.phases.parser.models.schemas

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.common.JvmClassName
import io.github.diskria.lapis.ksp.phases.parser.models.common.SymbolSource

class ParsedSchema(
    val classDeclaration: KSClassDeclaration,
    val isTopLevel: Boolean,
    val hasPackageName: Boolean,
    val originClassDeclaration: KSClassDeclaration?,
    val originJvmClassName: JvmClassName?,
    val hasClassAnnotation: Boolean,
    val hasInnerClassAnnotation: Boolean,
    val hasLocalClassAnnotation: Boolean,
    val hasAnonymousClassAnnotation: Boolean,
    val descriptors: List<ParsedDescriptor>,
    val nestedSchemas: List<ParsedSchema>,
) : SymbolSource {
    override val symbol: KSNode = classDeclaration
}
