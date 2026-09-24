package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.*

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
    val typeParameters: List<ParsedTypeParameter>,
    val companionObject: CompanionObject?,
    val constructors: List<Constructor>,
    val properties: List<Property>,
    val functions: List<Function>,
    val annotations: ParsedAnnotations,
    override val node: KSNode = classDeclaration,
) : NodeHolder {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val node: KSNode,
    ) : NodeHolder {

        class Parameter(
            val name: String,
            val type: ParsedType,
            val annotations: ParsedAnnotations,
            override val node: KSNode,
        ) : NodeHolder
    }

    class Property(
        val name: String,
        val type: ParsedType,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val hasExtensionReceiver: Boolean,
        val typeParameters: List<ParsedTypeParameter>,
        val annotations: ParsedAnnotations,
        val getter: Getter?,
        val setter: Setter?,
        override val node: KSNode,
    ) : NodeHolder {

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
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val extensionReceiverType: ParsedType?,
        val annotations: ParsedAnnotations,
        val typeParameters: List<ParsedTypeParameter>,
        override val node: KSNode,
    ) : NodeHolder {

        class Parameter(
            val name: String,
            val type: ParsedType,
            val annotations: ParsedAnnotations,
            override val node: KSNode,
        ) : NodeHolder
    }

    class CompanionObject(
        val name: String,
        val isPublic: Boolean,
        val functions: List<Function>,
        override val node: KSNode,
    ) : NodeHolder
}

sealed interface ParsedType : NodeHolder

class ValidType(
    val type: KSType,
    val isAny: Boolean,
    val isUnit: Boolean,
    val classDeclaration: KSClassDeclaration?,
    val packageName: String?,
    val qualifiedName: String?,
    val isInterface: Boolean,
    override val node: KSNode,
) : ParsedType

class InvalidType(override val node: KSNode) : ParsedType

class ParsedTypeParameter(
    val name: String,
    val variance: Variance,
    val isReified: Boolean,
    val bounds: List<ParsedType>,
    override val node: KSTypeParameter,
) : NodeHolder
