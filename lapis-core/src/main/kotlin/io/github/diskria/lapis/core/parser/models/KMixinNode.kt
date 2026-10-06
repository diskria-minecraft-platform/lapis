package io.github.diskria.lapis.core.parser.models

import io.github.diskria.lapis.core.utils.Variance

class KMixinNode<O>(
    val origin: O?,
    val name: String,
    val type: ParsedType,
    val isClass: Boolean,
    val isInterface: Boolean,
    val isOpen: Boolean,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isTopLevel: Boolean,
    val isPublic: Boolean,
    val typeParameters: List<TypeParameterNode>,
    val companionObject: CompanionObject?,
    val constructors: List<Constructor>,
    val properties: List<Property>,
    val functions: List<Function>,
    val annotations: AnnotationsContainer,
    override val node: Node,
) : NodeHolder {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val node: Node,
    ) : NodeHolder {

        class Parameter(
            val name: String?,
            val type: ParsedType,
            val annotations: AnnotationsContainer,
            override val node: Node,
        ) : NodeHolder
    }

    class Property(
        val name: String,
        val type: ParsedType,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val hasExtensionReceiver: Boolean,
        val contextParameters: List<ContextParameterNode>,
        val typeParameters: List<TypeParameterNode>,
        val annotations: AnnotationsContainer,
        val getter: Getter?,
        val setter: Setter?,
        override val node: Node,
    ) : NodeHolder {

        class Getter(
            val jvmName: String?,
            val annotations: AnnotationsContainer,
        )

        class Setter(
            val jvmName: String?,
        )
    }

    class Function(
        val name: String,
        val jvmName: String?,
        val parameters: List<Parameter>,
        val contextParameters: List<ContextParameterNode>,
        val returnType: ParsedType?,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val extensionReceiverType: ParsedType?,
        val annotations: AnnotationsContainer,
        val typeParameters: List<TypeParameterNode>,
        val isSuspending: Boolean,
        override val node: Node,
    ) : NodeHolder {

        class Parameter(
            val name: String?,
            val type: ParsedType,
            val annotations: AnnotationsContainer,
            override val node: Node,
        ) : NodeHolder
    }

    class CompanionObject(
        val name: String,
        val isPublic: Boolean,
        val functions: List<Function>,
        override val node: Node,
    ) : NodeHolder
}

class ContextParameterNode(
    val name: String?,
    val type: ParsedType,
    val annotations: AnnotationsContainer,
    override val node: Node,
) : NodeHolder

sealed interface ParsedType

sealed interface ParsedValidType : ParsedType {
    val type: Type
}

object ParsedInvalidType : ParsedType

class ParsedClassType(
    val packageName: String,
    val qualifiedName: String?,
    val arguments: List<TypeArgument>,
    val actualType: ParsedClassType?,
    val functionalType: FunctionalType?,
    override val type: Type,
) : ParsedValidType {

    val actualOrSelf: ParsedClassType get() = actualType ?: this

    sealed interface TypeArgument
    object StarProjectionArgument : TypeArgument
    class GenericTypeArgument(val type: ParsedValidType, val variance: Variance) : TypeArgument

    class FunctionalType(
        val contextTypes: List<ParsedValidType>,
        val receiverType: ParsedValidType?,
        val parameters: List<Parameter>,
        val returnType: ParsedValidType,
        val isSuspending: Boolean,
    ) {
        class Parameter(val name: String?, val type: ParsedValidType)
    }
}

class ParsedTypeArgument(
    val name: String,
    override val type: Type,
) : ParsedValidType

class TypeParameterNode(
    val name: String,
    val bounds: List<ParsedType>,
    val isReified: Boolean,
    override val node: Node,
) : NodeHolder
