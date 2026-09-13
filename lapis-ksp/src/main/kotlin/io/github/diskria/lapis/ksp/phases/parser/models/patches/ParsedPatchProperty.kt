package io.github.diskria.lapis.ksp.phases.parser.models.patches

import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import javax.lang.model.element.Modifier

class ParsedPatchProperty(
    override val symbol: KSNode,
    val name: String,
    val type: KSType?,
    val isPublic: Boolean,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val hasExtensionReceiver: Boolean,
    val explicitMappingName: String?,
    val hasExtensionAnnotation: Boolean,
    val hasShadowAnnotation: Boolean,
    val shadowModifiers: List<Modifier>,
    val getter: ParsedPatchPropertyGetter?,
    val setter: ParsedPatchPropertySetter?,
) : SymbolSource

class ParsedPatchPropertyGetter(
    val jvmName: String?,
    val annotations: List<ParsedAnnotation>,
)

class ParsedPatchPropertySetter(
    val jvmName: String?,
)
