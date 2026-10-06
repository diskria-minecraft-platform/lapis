package io.github.diskria.lapis.core.parser.models

import io.github.diskria.lapis.core.extensions.isSubpackageOf
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.parser.models.AnnotationNode.Argument
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class AnnotationNode(
    val type: ParsedType,
    val arguments: List<Argument>,
    override val node: Node,
) : NodeHolder {

    sealed interface Argument : NodeHolder {

        sealed interface Value
        sealed interface ValidValue : Value
        object InvalidValue : Value

        class BooleanValue(val boolean: Boolean) : ValidValue
        class ByteValue(val byte: Byte) : ValidValue
        class ShortValue(val short: Short) : ValidValue
        class IntValue(val int: Int) : ValidValue
        class LongValue(val long: Long) : ValidValue
        class CharValue(val char: Char) : ValidValue
        class FloatValue(val float: Float) : ValidValue
        class DoubleValue(val double: Double) : ValidValue
        class StringValue(val string: String) : ValidValue
        class TypeValue(val type: ParsedType) : ValidValue
        class EnumValue(val type: ParsedType, val name: String) : ValidValue
        class AnnotationValue(val annotation: AnnotationNode) : ValidValue
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

class AnnotationsContainer(
    val apiAnnotations: List<AnnotationNode>,
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
        if (type is ParsedClassType && type.qualifiedName == qualifiedNameOf<E>()) {
            enumEntries<E>().find { it.name == name }
        } else null

    inline fun <reified A : Annotation> findApiAnnotation(): AnnotationNode? =
        apiAnnotations.firstNotNullOfOrNull { annotation ->
            if (annotation.type is ParsedClassType && annotation.type.qualifiedName == qualifiedNameOf<A>()) annotation
            else null
        }

    data class ApiScalarArgument<T>(val value: T, val node: NodeHolder)
    data class ApiArrayArgument<T>(val elements: List<T>, val node: NodeHolder)

    companion object {
        private const val API_ANNOTATIONS_PACKAGE = "io.github.diskria.lapis.annotations"

        fun of(annotations: List<AnnotationNode>): AnnotationsContainer {
            val apiAnnotations = mutableListOf<AnnotationNode>()
            val externalAnnotations = mutableListOf<AnnotationNode>()
            annotations.forEach { annotation ->
                if (annotation.type is ParsedClassType &&
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
