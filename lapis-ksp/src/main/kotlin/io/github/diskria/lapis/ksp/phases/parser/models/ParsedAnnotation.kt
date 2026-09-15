package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

class ParsedAnnotation(
    val typeClassDeclaration: KSClassDeclaration?,
    val isLapisApi: Boolean,
    val qualifiedName: String?,
    val arguments: List<Argument>
) {
    sealed interface Argument {

        val name: String
        val isExplicit: Boolean

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
        class TypeValue(val type: KSType) : Value
        class EnumValue(
            val enumClassDeclaration: KSClassDeclaration,
            val enumQualifiedName: String?,
            val entryName: String
        ) : Value

        class AnnotationValue(val annotation: ParsedAnnotation) : Value
    }

    class ScalarArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val value: Argument.Value,
    ) : Argument

    class ArrayArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val elements: List<Argument.Value>,
    ) : Argument
}

inline fun <reified A : Annotation> List<ParsedAnnotation?>.findLapisApiAnnotation(): ParsedAnnotation? =
    find { it != null && it.isLapisApi && it.qualifiedName == requireQualifiedName<A>() }

inline fun <reified A : Annotation> ParsedAnnotation.findArgument(property: KProperty1<A, String>): String? =
    arguments.find { it.name == property.name }?.let { it as ParsedAnnotation.ScalarArgument }?.value
        ?.let { it as? ParsedAnnotation.Argument.StringValue }?.string

inline fun <reified A : Annotation> ParsedAnnotation.findArgument(property: KProperty1<A, KClass<*>>): KSType? =
    arguments.find { it.name == property.name }?.let { it as ParsedAnnotation.ScalarArgument }?.value
        ?.let { it as? ParsedAnnotation.Argument.TypeValue }?.type

inline fun <reified A : Annotation, reified E : Enum<E>> ParsedAnnotation.findArgument(
    property: KProperty1<A, E>
): E? =
    arguments.find { it.name == property.name }?.let { it as ParsedAnnotation.ScalarArgument }?.value?.let {
        val enumValue = it as? ParsedAnnotation.Argument.EnumValue ?: return null
        if (enumValue.enumQualifiedName != requireQualifiedName<E>()) return null
        enumEntries<E>().find { entry -> entry.name == enumValue.entryName } ?: return null
    }

inline fun <reified A : Annotation, reified E : Enum<E>> ParsedAnnotation.findArgument(
    property: KProperty1<A, Array<out E>>,
): List<E>? =
    arguments.find { it.name == property.name }?.let { it as ParsedAnnotation.ArrayArgument }?.elements?.map {
        val enumValue = it as? ParsedAnnotation.Argument.EnumValue ?: return null
        if (enumValue.enumQualifiedName != requireQualifiedName<E>()) return null
        enumEntries<E>().find { entry -> entry.name == enumValue.entryName } ?: return null
    }
