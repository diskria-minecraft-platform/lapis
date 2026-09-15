package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.annotations.InitStrategy
import javax.lang.model.element.Modifier

class ParsedPatch(
    val name: String?,
    val env: Env?,
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
    val companionObjects: List<CompanionObject>,
    val constructors: List<Constructor>,
    val bodyProperties: List<Property>,
    val functions: List<Function>,
    val annotations: List<ParsedAnnotation?>,
    override val symbol: KSNode = classDeclaration,
) : SymbolSource {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Parameter(
            val type: KSType?,
            val hasOriginAnnotation: Boolean,
            override val symbol: KSNode,
        ) : SymbolSource
    }

    class Property(
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
        val annotations: List<ParsedAnnotation?>,
        val getter: Getter?,
        val setter: Setter?,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Getter(
            val jvmName: String?,
            val annotations: List<ParsedAnnotation?>,
        )

        class Setter(
            val jvmName: String?,
        )
    }

    class Function(
        val name: String,
        val jvmName: String?,
        val parameters: List<Parameter>,
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
        val annotations: List<ParsedAnnotation?>,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Parameter(
            val name: String?,
            val type: KSType?,
            val annotations: List<ParsedAnnotation?>,
            override val symbol: KSNode,
        ) : SymbolSource
    }

    class CompanionObject(
        val isPublic: Boolean,
        val functions: List<Function>,
        override val symbol: KSNode,
    ) : SymbolSource
}
