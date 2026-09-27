package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.*

class KMixinNode(
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
    override val node: KSClassDeclaration,
) : NodeHolder {

    class Constructor(
        val isPublic: Boolean,
        val parameters: List<Parameter>,
        override val node: KSNode,
    ) : NodeHolder {

        class Parameter(
            val name: NameNode,
            val type: TypeNode,
            val annotations: AnnotationNodeContainer,
            override val node: KSNode,
        ) : NodeHolder
    }

    class Property(
        val name: NameNode,
        val type: TypeNode,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val hasExtensionReceiver: Boolean,
        val typeParameters: List<TypeParameterNode>,
        val annotations: AnnotationNodeContainer,
        val getter: Getter?,
        val setter: Setter?,
        override val node: KSNode,
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
        val returnType: TypeNode?,
        val isPublic: Boolean,
        val isOpen: Boolean,
        val isAbstract: Boolean,
        val extensionReceiverType: TypeNode?,
        val annotations: AnnotationNodeContainer,
        val typeParameters: List<TypeParameterNode>,
        override val node: KSNode,
    ) : NodeHolder {

        class Parameter(
            val name: NameNode,
            val type: TypeNode,
            val annotations: AnnotationNodeContainer,
            override val node: KSNode,
        ) : NodeHolder
    }

    class CompanionObject(
        val name: NameNode,
        val isPublic: Boolean,
        val functions: List<Function>,
        override val node: KSNode,
    ) : NodeHolder
}

sealed interface TypeNode : NodeHolder

sealed interface ValidTypeNode : TypeNode {
    val ksType: KSType
    val isNullable: Boolean
}

class ClassTypeNode(
    val packageName: NameNode,
    val qualifiedName: NameNode,
    val arguments: List<Argument>,
    val canonicalType: ClassTypeNode?,
    override val isNullable: Boolean,
    override val ksType: KSType,
    override val node: KSNode,
) : ValidTypeNode {
    sealed interface Argument : NodeHolder
    class StarArgument(override val node: KSNode) : Argument
    class InvariantArgument(val type: TypeNode, override val node: KSNode) : Argument
    class CovariantArgument(val type: TypeNode, override val node: KSNode) : Argument
    class ContravariantArgument(val type: TypeNode, override val node: KSNode) : Argument
}

class TypeArgumentNode(
    val name: NameNode,
    override val isNullable: Boolean,
    override val ksType: KSType,
    override val node: KSNode,
) : ValidTypeNode

class InvalidTypeNode(override val node: KSNode) : TypeNode

class TypeParameterNode(
    val name: NameNode,
    val variance: Variance,
    val isReified: Boolean,
    val bounds: List<TypeNode>,
    override val node: KSTypeParameter,
) : NodeHolder

sealed interface NameNode : NodeHolder
class ValidNameNode(val name: String, override val node: KSNode) : NameNode
class InvalidNameNode(override val node: KSNode) : NameNode
