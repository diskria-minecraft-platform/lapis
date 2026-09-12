package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.parser.models.common.ParsedAnnotation
import io.github.diskria.lapis.ksp.phases.parser.models.common.SymbolSource
import javax.lang.model.element.Modifier

class ParsedPatchFunction(
    override val symbol: KSNode,

    val name: String,
    val jvmName: String?,
    val parameters: List<ParsedPatchFunctionParameter>,
    val returnType: KSType?,
    val hasTypeParameters: Boolean,

    val isPublic: Boolean,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val extensionReceiverClassDeclaration: KSClassDeclaration?,

    val hasExtensionAnnotation: Boolean,
    val hasShadowAnnotation: Boolean,
    val explicitMappingName: String?,
    val shadowModifiers: List<Modifier>,

    val annotations: List<ParsedAnnotation>,
) : SymbolSource
