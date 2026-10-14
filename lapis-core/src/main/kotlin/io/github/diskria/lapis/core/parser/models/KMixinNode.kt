package io.github.diskria.lapis.core.parser.models

import io.github.diskria.lapis.core.utils.Variance

class KMixinNode<O>(
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
    val origin: O?,
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
        val hasExplicitType: Boolean,
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
        val returnType: ParsedType,
        val hasExplicitReturnType: Boolean,
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
    val typeArguments: List<TypeArgument>,
    val actualType: ParsedClassType?,
    val functionalType: FunctionalType?,
    override val type: Type,
) : ParsedValidType {

    val actualOrThis: ParsedClassType get() = actualType ?: this

    sealed interface TypeArgument
    object StarProjectionArgument : TypeArgument
    class TypeProjectionArgument(val type: ParsedValidType, val variance: Variance) : TypeArgument

    class FunctionalType private constructor(
        val contextTypes: List<ParsedValidType>,
        val receiverType: ParsedValidType?,
        val parameters: List<Parameter>,
        val returnType: ParsedValidType,
        val isSuspending: Boolean,
    ) {
        class Parameter(val name: String?, val type: ParsedValidType)

        companion object {
            fun <A, T> parse(
                typeArguments: List<A>,
                nothingType: T,
                nullableAnyType: T,
                isSuspending: Boolean,
                getEffectiveType: (argument: A, fallback: T) -> T,
                parseType: (type: T) -> ParsedType,
                getContextParametersCount: () -> Int,
                isExtension: () -> Boolean,
                getParameterName: (argument: A, type: T) -> String?,
            ): FunctionalType? {
                val deque = ArrayDeque(typeArguments)
                val contextTypes = List(getContextParametersCount()) {
                    val rawType = deque.removeFirstOrNull() ?: return null
                    parseType(getEffectiveType(rawType, nothingType)) as? ParsedValidType ?: return null
                }
                val receiverType = if (isExtension()) {
                    val rawType = deque.removeFirstOrNull() ?: return null
                    parseType(getEffectiveType(rawType, nothingType)) as? ParsedValidType ?: return null
                } else {
                    null
                }
                val returnRawType = deque.removeLastOrNull() ?: return null
                val returnType = parseType(getEffectiveType(returnRawType, nullableAnyType))
                    as? ParsedValidType ?: return null
                val parameters = deque.map { argument ->
                    val effectiveType = getEffectiveType(argument, nothingType)
                    val type = parseType(effectiveType) as? ParsedValidType ?: return null
                    ParsedClassType.FunctionalType.Parameter(
                        name = getParameterName(argument, effectiveType),
                        type = type,
                    )
                }
                return ParsedClassType.FunctionalType(
                    contextTypes = contextTypes,
                    receiverType = receiverType,
                    parameters = parameters,
                    returnType = returnType,
                    isSuspending = isSuspending,
                )
            }
        }
    }
}

class ParsedTypeParameterType(
    val name: String,
    override val type: Type,
) : ParsedValidType

class TypeParameterNode(
    val name: String,
    val bounds: List<ParsedType>,
    val isReified: Boolean,
    val annotations: AnnotationsContainer,
    override val node: Node,
) : NodeHolder
