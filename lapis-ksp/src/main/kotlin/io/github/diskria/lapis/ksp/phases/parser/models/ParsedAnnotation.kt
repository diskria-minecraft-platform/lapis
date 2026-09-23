package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation.Argument
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

sealed interface ParsedAnnotation : KspNode {

    sealed interface Argument : KspNode {

        sealed interface Value<out T> {
            val raw: T
        }

        class BooleanValue(override val raw: Boolean) : Value<Boolean>
        class ByteValue(override val raw: Byte) : Value<Byte>
        class ShortValue(override val raw: Short) : Value<Short>
        class IntValue(override val raw: Int) : Value<Int>
        class LongValue(override val raw: Long) : Value<Long>
        class CharValue(override val raw: Char) : Value<Char>
        class FloatValue(override val raw: Float) : Value<Float>
        class DoubleValue(override val raw: Double) : Value<Double>
        class StringValue(override val raw: String) : Value<String>
        class TypeValue(override val raw: ValidType) : Value<ValidType>

        class EnumValue(
            val enumClassDeclaration: KSClassDeclaration,
            val enumQualifiedName: String?,
            entryClassDeclaration: KSClassDeclaration,
            val entryName: String,
        ) : Value<KSClassDeclaration> {

            override val raw = entryClassDeclaration

            inline fun <reified E : Enum<E>> asEnum(): E? {
                if (enumQualifiedName != requireQualifiedName<E>()) return null
                return enumEntries<E>().find { it.name == entryName }
            }
        }

        class AnnotationValue(override val raw: ParsedAnnotation) : Value<ParsedAnnotation>
    }

    sealed interface ValidArgument : Argument {
        val name: String
        val isExplicit: Boolean
    }

    class ScalarArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val value: Argument.Value<*>,
        override val node: KSNode,
    ) : ValidArgument

    class ArrayArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val elements: List<Argument.Value<*>>,
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

class ParsedAnnotations(
    val api: List<ValidAnnotation>,
    val external: List<ParsedAnnotation>,
) {
    inline fun <reified A : Annotation> hasApiAnnotation(): Boolean = findApiAnnotation<A>() != null

    @JvmName("findApiCommonTypeScalarArgument")
    inline fun <reified A : Annotation, reified R> findApiArgument(
        property: KProperty1<A, R>
    ): ApiScalarArgument<R>? = findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
        if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name) {
            val raw = argument.value.raw
            if (raw is R) ApiScalarArgument(raw, argument) else null
        } else null
    }

    @JvmName("findApiValidTypeScalarArgument")
    inline fun <reified A : Annotation> findApiArgument(
        property: KProperty1<A, KClass<*>>
    ): ApiScalarArgument<ValidType>? = findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
        if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name) {
            val typeValue = argument.value as? ParsedAnnotation.Argument.TypeValue ?: return@firstNotNullOfOrNull null
            ApiScalarArgument(typeValue.raw, argument)
        } else null
    }

    @JvmName("findApiEnumTypeScalarArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(
        property: KProperty1<A, E>
    ): ApiScalarArgument<E>? = findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
        if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name) {
            val enumValue = argument.value as? ParsedAnnotation.Argument.EnumValue ?: return@firstNotNullOfOrNull null
            val enum = enumValue.asEnum<E>() ?: return@firstNotNullOfOrNull null
            ApiScalarArgument(enum, argument)
        } else null
    }

    @JvmName("findApiEnumTypeArrayArgument")
    inline fun <reified A : Annotation, reified E : Enum<E>> findApiArgument(
        property: KProperty1<A, Array<out E>>
    ): ApiArrayArgument<E>? = findApiAnnotation<A>()?.arguments?.firstNotNullOfOrNull { argument ->
        if (argument is ParsedAnnotation.ArrayArgument && argument.name == property.name) {
            val elements = argument.elements.map { element ->
                val enumValue = element as? ParsedAnnotation.Argument.EnumValue ?: return@firstNotNullOfOrNull null
                enumValue.asEnum<E>() ?: return@firstNotNullOfOrNull null
            }
            ApiArrayArgument(elements, argument)
        } else null
    }

    inline fun <reified A : Annotation> findApiAnnotation(): ValidAnnotation? =
        api.firstNotNullOfOrNull { if (it.type.qualifiedName == requireQualifiedName<A>()) it else null }

    data class ApiScalarArgument<T>(
        val value: T,
        val node: KspNode,
    )

    data class ApiArrayArgument<T>(
        val elements: List<T>,
        val node: KspNode,
    )
}
