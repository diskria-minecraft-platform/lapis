package io.github.diskria.lapis.core.parser.models

import io.github.diskria.lapis.core.utils.Variance

class KMixinNode<O>(
    val origin: O?,
    val name: NameNode,
    val type: TypeNode,
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
    val annotations: AnnotationNodeContainer,
    override val node: Node,
) : NodeHolder {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val node: Node,
    ) : NodeHolder {

        class Parameter(
            val name: NameNode,
            val type: TypeNode,
            val annotations: AnnotationNodeContainer,
            override val node: Node,
        ) : NodeHolder
    }

    class Property(
        val name: NameNode,
        val type: TypeNode,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val hasExtensionReceiver: Boolean,
        val contextParameters: List<ContextParameterNode>,
        val typeParameters: List<TypeParameterNode>,
        val annotations: AnnotationNodeContainer,
        val getter: Getter?,
        val setter: Setter?,
        override val node: Node,
    ) : NodeHolder {

        class Getter(
            val jvmName: String?,
            val annotations: AnnotationNodeContainer,
        )

        class Setter(
            val jvmName: String?,
        )
    }

    class Function(
        val name: NameNode,
        val jvmName: String?,
        val parameters: List<Parameter>,
        val contextParameters: List<ContextParameterNode>,
        val returnType: TypeNode?,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val extensionReceiverType: TypeNode?,
        val annotations: AnnotationNodeContainer,
        val typeParameters: List<TypeParameterNode>,
        val isSuspending: Boolean,
        override val node: Node,
    ) : NodeHolder {

        class Parameter(
            val name: NameNode,
            val type: TypeNode,
            val annotations: AnnotationNodeContainer,
            override val node: Node,
        ) : NodeHolder
    }

    class CompanionObject(
        val name: NameNode,
        val isPublic: Boolean,
        val functions: List<Function>,
        override val node: Node,
    ) : NodeHolder
}

class ContextParameterNode(
    val name: NameNode,
    val type: TypeNode,
    val annotations: AnnotationNodeContainer,
    override val node: Node,
) : NodeHolder

sealed interface TypeNode : NodeHolder

sealed interface ValidTypeNode : TypeNode {
    val type: Type
    val isNullable: Boolean
}

class ClassTypeNode(
    val packageName: NameNode,
    val qualifiedName: NameNode,
    val arguments: List<TypeArgument>,
    val canonicalType: ClassTypeNode?,
    val functionalType: FunctionalType?,
    override val type: Type,
    override val isNullable: Boolean,
    override val node: Node,
) : ValidTypeNode {
    sealed interface TypeArgument : NodeHolder
    class StarProjectionArgument(override val node: Node) : TypeArgument
    class GenericTypeArgument(val type: TypeNode, val variance: Variance, override val node: Node) : TypeArgument

    class FunctionalType(
        val contextTypes: List<TypeNode>,
        val receiverType: TypeNode?,
        val parameters: List<Parameter>,
        val returnType: TypeNode,
        val isSuspending: Boolean,
    ) {
        class Parameter(val name: String?, val type: TypeNode)
    }
}

class TypeArgumentNode(
    val name: NameNode,
    override val type: Type,
    override val isNullable: Boolean,
    override val node: Node,
) : ValidTypeNode

class InvalidTypeNode(override val node: Node) : TypeNode

class TypeParameterNode(
    val name: NameNode,
    val bounds: List<TypeNode>,
    val isReified: Boolean,
    override val node: Node,
) : NodeHolder

sealed interface NameNode : NodeHolder
class ValidNameNode(val name: String, override val node: Node) : NameNode
class InvalidNameNode(override val node: Node) : NameNode
