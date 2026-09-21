package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType

class ParsedPatch(
    val name: String,
    val isClass: Boolean,
    val isInterface: Boolean,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isTopLevel: Boolean,
    val hasPackageName: Boolean,
    val isPublic: Boolean,
    val classDeclaration: KSClassDeclaration,
    val companionObject: CompanionObject?,
    val constructors: List<Constructor>,
    val properties: List<Property>,
    val functions: List<Function>,
    val annotations: ParsedAnnotations,
    override val symbol: KSNode = classDeclaration,
) : SymbolSource {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Parameter(
            val name: ParsedName,
            val type: ParsedType,
            val annotations: ParsedAnnotations,
            override val symbol: KSNode,
        ) : SymbolSource
    }

    class Property(
        val name: String,
        val type: ParsedType,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val hasExtensionReceiver: Boolean,
        val annotations: ParsedAnnotations,
        val getter: Getter?,
        val setter: Setter?,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Getter(
            val jvmName: String?,
            val annotations: ParsedAnnotations,
        )

        class Setter(
            val jvmName: String?,
        )
    }

    class Function(
        val name: String,
        val jvmName: String?,
        val parameters: List<Parameter>,
        val returnType: ParsedType,
        val hasTypeParameters: Boolean,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val extensionReceiverType: ParsedType?,
        val annotations: ParsedAnnotations,
        override val symbol: KSNode,
    ) : SymbolSource {

        class Parameter(
            val name: ParsedName,
            val type: ParsedType,
            val annotations: ParsedAnnotations,
            override val symbol: KSNode,
        ) : SymbolSource
    }

    class CompanionObject(
        val name: String,
        val isPublic: Boolean,
        val functions: List<Function>,
        override val symbol: KSNode,
    ) : SymbolSource
}

sealed interface ParsedName
class ValidName(val name: String) : ParsedName
object InvalidName : ParsedName

sealed interface ParsedType
class ValidType(
    val type: KSType,
    val isAny: Boolean,
    val isUnit: Boolean,
    val isInterface: Boolean,
    val packageName: ValidName,
    val qualifiedName: ValidName,
    val classDeclaration: KSClassDeclaration,
) : ParsedType {
    fun isAssignableFrom(parent: ValidType): Boolean = type.isAssignableFrom(parent.type)
}

object InvalidType : ParsedType
