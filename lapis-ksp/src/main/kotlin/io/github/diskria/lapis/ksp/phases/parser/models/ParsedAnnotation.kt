package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.extensions.qualifiedNameOf
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation.Argument
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

sealed interface ParsedAnnotation : NodeHolder {

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
        class TypeValue(val type: ParsedType) : Value
        class EnumValue(val classDeclaration: KSClassDeclaration, val name: String) : Value
        class AnnotationValue(val annotation: ParsedAnnotation) : Value
    }

    sealed interface ValidArgument : Argument {
        val name: String
        val isExplicit: Boolean
    }

    class ScalarArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val value: Argument.Value,
        override val node: KSNode,
    ) : ValidArgument

    class ArrayArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val elements: List<Argument.Value>,
        override val node: KSNode,
    ) : ValidArgument

    class InvalidArgument(override val node: KSNode) : Argument
}

class ValidAnnotation(
    val type: ValidType,
    val arguments: List<Argument>,
    override val node: KSNode,
) : ParsedAnnotation

class InvalidAnnotation(override val node: KSNode) : ParsedAnnotation

class ParsedAnnotations(val api: List<ValidAnnotation>, val external: List<ParsedAnnotation>) {

    inline fun <reified A : Annotation> hasApiAnnotation(): Boolean = findApiAnnotation<A>() != null

    @JvmName("findApiStringTypeScalarArgument")
    inline fun <reified A : Annotation> findApiArgument(property: KProperty1<out A, String>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name &&
                argument.value is ParsedAnnotation.Argument.StringValue
            ) {
                ApiScalarArgument(argument.value.string, argument)
            } else null
        }

    @JvmName("findApiValidTypeScalarArgument")
    inline fun <reified A : Annotation> findApiArgument(property: KProperty1<out A, KClass<*>>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name &&
                argument.value is ParsedAnnotation.Argument.TypeValue
            ) {
                ApiScalarArgument(argument.value.type, argument)
            } else null
        }

    @JvmName("findApiEnumTypeScalarArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(property: KProperty1<out A, E>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name &&
                argument.value is ParsedAnnotation.Argument.EnumValue
            ) {
                argument.value.getTypedOrNull<E>()?.let { ApiScalarArgument(it, argument) }
            } else null
        }

    @JvmName("findApiEnumTypeArrayArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(property: KProperty1<A, Array<out E>>) =
        findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
            if (argument is ParsedAnnotation.ArrayArgument && argument.name == property.name) {
                val elements = argument.elements.map { element ->
                    val enumValue = element as? ParsedAnnotation.Argument.EnumValue
                    enumValue?.getTypedOrNull<E>() ?: return@firstNotNullOfOrNull null
                }
                ApiArrayArgument(elements, argument)
            } else null
        }

    inline fun <reified E : Enum<E>> ParsedAnnotation.Argument.EnumValue.getTypedOrNull(): E? =
        if (classDeclaration.qualifiedName?.asString() == qualifiedNameOf<E>()) {
            enumEntries<E>().find { it.name == name }
        } else null

    inline fun <reified A : Annotation> findApiAnnotation(): ValidAnnotation? =
        api.firstNotNullOfOrNull { annotation ->
            if (annotation.type.qualifiedName == qualifiedNameOf<A>()) annotation
            else null
        }

    data class ApiScalarArgument<T>(
        val value: T,
        val node: NodeHolder,
    )

    data class ApiArrayArgument<T>(
        val elements: List<T>,
        val node: NodeHolder,
    )
}
