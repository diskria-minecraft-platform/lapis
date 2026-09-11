package io.github.diskria.lapis.ksp.phases.parser.models.schemas

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.phases.parser.models.common.SymbolSource

class ParsedDescriptor(
    val name: String,
    val classDeclaration: KSClassDeclaration,
    val isObject: Boolean,
    val hasFieldAnnotation: Boolean,
    val hasMethodAnnotation: Boolean,
    val hasConstructorAnnotation: Boolean,
    val isStatic: Boolean,
    val hasMappingNameAnnotation: Boolean,
    val explicitMappingName: String?,
    val genericArgument: ParsedDescriptorGenericArgument?,
) : SymbolSource {
    override val symbol: KSNode = classDeclaration
}
