package io.github.diskria.lapis.core.parser.models

import io.github.diskria.lapis.core.extensions.isSubpackageOf
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.parser.models.AnnotationNode.Argument
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

sealed interface AnnotationNode : NodeHolder {

    sealed interface Argument : NodeHolder {

        sealed interface Value
        class BooleanValue(val boolean: Boolean) : Value
        class ByteValue(val byte: Byte) : Value
        class ShortValue(val short: Short) : Value
        class IntValue(val int: Int) : Value
        class LongValue(val long: Long) : Value
        class CharValue(val char: Char) : Value
        class FloatValue(val float: Float) : Value
        class DoubleValue(val double: Double) : Value
        class StringValue(val string: String) : Value
        class TypeValue(val type: TypeNode, override val node: Node) : Value, NodeHolder
        class EnumValue(val type: TypeNode, val name: String, override val node: Node) : Value, NodeHolder
        class AnnotationValue(val annotation: AnnotationNode) : Value
    }

    sealed interface ValidArgument : Argument {
        val name: String?
        val isExplicit: Boolean
    }

    class ScalarArgument(
        override val name: String?,
        override val isExplicit: Boolean,
        val value: Argument.Value,
        override val node: Node,
    ) : ValidArgument

    class ArrayArgument(
        override val name: String?,
        override val isExplicit: Boolean,
        val elements: List<Argument.Value>,
        override val node: Node,
    ) : ValidArgument

    class InvalidArgument(override val node: Node) : Argument
}

class ValidAnnotationNode(
    val type: ValidTypeNode,
    val arguments: List<Argument>,
    override val node: Node,
) : AnnotationNode

class InvalidAnnotationNode(override val node: Node) : AnnotationNode

class AnnotationsContainer(
    val apiAnnotations: List<ValidAnnotationNode>,
    val externalAnnotations: List<AnnotationNode>,
) {
    inline fun <reified A : Annotation> hasApiAnnotation(): Boolean = findApiAnnotation<A>() != null

    @JvmName("findApiStringTypeScalarArgument")
    inline fun <reified A : Annotation> findApiArgument(property: KProperty1<out A, String>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is AnnotationNode.ScalarArgument &&
                argument.name == property.name &&
                argument.value is Argument.StringValue
            ) {
                ApiScalarArgument(argument.value.string, argument)
            } else null
        }

    @JvmName("findApiValidTypeScalarArgument")
    inline fun <reified A : Annotation> findApiArgument(property: KProperty1<out A, KClass<*>>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is AnnotationNode.ScalarArgument &&
                argument.name == property.name &&
                argument.value is Argument.TypeValue
            ) {
                ApiScalarArgument(argument.value.type, argument)
            } else null
        }

    @JvmName("findApiEnumTypeScalarArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(property: KProperty1<out A, E>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is AnnotationNode.ScalarArgument &&
                argument.name == property.name &&
                argument.value is Argument.EnumValue
            ) {
                argument.value.getTypedOrNull<E>()?.let { ApiScalarArgument(it, argument) }
            } else null
        }

    @JvmName("findApiEnumTypeArrayArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(property: KProperty1<A, Array<out E>>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is AnnotationNode.ArrayArgument && argument.name == property.name) {
                val elements = argument.elements.map { element ->
                    val enumValue = element as? Argument.EnumValue
                    enumValue?.getTypedOrNull<E>() ?: return@firstNotNullOfOrNull null
                }
                ApiArrayArgument(elements, argument)
            } else null
        }

    inline fun <reified E : Enum<E>> Argument.EnumValue.getTypedOrNull(): E? =
        if (type is ClassTypeNode && type.qualifiedName == qualifiedNameOf<E>()) {
            enumEntries<E>().find { it.name == name }
        } else null

    inline fun <reified A : Annotation> findApiAnnotation(): ValidAnnotationNode? =
        apiAnnotations.firstNotNullOfOrNull { annotation ->
            if (annotation.type is ClassTypeNode &&
                annotation.type.qualifiedName == qualifiedNameOf<A>()
            ) annotation
            else null
        }

    data class ApiScalarArgument<T>(val value: T, val node: NodeHolder)
    data class ApiArrayArgument<T>(val elements: List<T>, val node: NodeHolder)

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"

        fun of(annotations: List<AnnotationNode>): AnnotationsContainer {
            val apiAnnotations = mutableListOf<ValidAnnotationNode>()
            val externalAnnotations = mutableListOf<AnnotationNode>()
            annotations.forEach { annotation ->
                if (annotation is ValidAnnotationNode &&
                    annotation.type is ClassTypeNode &&
                    annotation.type.packageName.isSubpackageOf(API_ANNOTATIONS_PACKAGE)
                ) {
                    apiAnnotations += annotation
                } else {
                    externalAnnotations += annotation
                }
            }
            return AnnotationsContainer(apiAnnotations, externalAnnotations)
        }
    }
}
