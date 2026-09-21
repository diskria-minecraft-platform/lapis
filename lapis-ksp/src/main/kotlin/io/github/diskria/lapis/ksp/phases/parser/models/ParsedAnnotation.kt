package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName
import io.github.diskria.lapis.ksp.phases.parser.models.ParsedAnnotation.Argument
import kotlin.enums.enumEntries
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

sealed interface ParsedAnnotation : SymbolSource {

    sealed interface Argument : SymbolSource {

        sealed interface Value

        sealed interface ValidValue : Value
        class BooleanValue(val boolean: Boolean) : ValidValue
        class ByteValue(val byte: Byte) : ValidValue
        class ShortValue(val short: Short) : ValidValue
        class IntValue(val int: Int) : ValidValue
        class LongValue(val long: Long) : ValidValue
        class CharValue(val char: Char) : ValidValue
        class FloatValue(val float: Float) : ValidValue
        class DoubleValue(val double: Double) : ValidValue
        class StringValue(val string: String) : ValidValue
        class TypeValue(val type: ValidType) : ValidValue
        class EnumValue(
            val enumClassDeclaration: KSClassDeclaration,
            val enumQualifiedName: String?,
            val entryName: String,
        ) : ValidValue {
            inline fun <reified E : Enum<E>> asEnum(): E? {
                if (enumQualifiedName != requireQualifiedName<E>()) return null
                return enumEntries<E>().find { it.name == entryName }
            }
        }

        class AnnotationValue(val annotation: ParsedAnnotation) : ValidValue

        object InvalidValue : Value
    }

    sealed interface ValidArgument : Argument {
        val name: String
        val isExplicit: Boolean
    }

    class ScalarArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val value: Argument.Value,
        override val symbol: KSNode,
    ) : ValidArgument

    class ArrayArgument(
        override val name: String,
        override val isExplicit: Boolean,
        val elements: List<Argument.Value>,
        override val symbol: KSNode,
    ) : ValidArgument

    class InvalidArgument(override val symbol: KSNode) : Argument
}

class ValidAnnotation(
    val typeClassDeclaration: KSClassDeclaration,
    val packageName: String,
    val qualifiedName: String,
    val arguments: List<Argument>,
    override val symbol: KSNode,
) : ParsedAnnotation

class InvalidAnnotation(override val symbol: KSNode) : ParsedAnnotation

class ParsedAnnotations(
    val api: List<ValidAnnotation>,
    val external: List<ParsedAnnotation>,
) {
    inline fun <reified A : Annotation> findApiAnnotation(): ValidAnnotation? =
        api.firstNotNullOfOrNull { if (it.qualifiedName == requireQualifiedName<A>()) it else null }
}

inline fun <reified A : Annotation> ValidAnnotation.findArgument(
    property: KProperty1<A, String>
): String? = findScalarArgumentValue<ParsedAnnotation.Argument.StringValue, String>(property) { it.string }

inline fun <reified A : Annotation> ValidAnnotation.findArgument(
    property: KProperty1<A, KClass<*>>
): ValidType? = findScalarArgumentValue<ParsedAnnotation.Argument.TypeValue, ValidType>(property) { it.type }

inline fun <reified A : Annotation, reified E : Enum<E>> ValidAnnotation.findArgument(
    property: KProperty1<A, E>
): E? = findScalarArgumentValue<ParsedAnnotation.Argument.EnumValue, E>(property) { it.asEnum() }

inline fun <reified A : Annotation, reified E : Enum<E>> ValidAnnotation.findArgument(
    property: KProperty1<A, Array<out E>>,
): List<E>? = findArrayArgumentValue<ParsedAnnotation.Argument.EnumValue, E>(property) { it.asEnum() }

inline fun <reified V : ParsedAnnotation.Argument.Value, R> ValidAnnotation.findScalarArgumentValue(
    property: KProperty1<out Annotation, Any>,
    transform: (V) -> R?,
): R? = arguments.firstNotNullOfOrNull { argument ->
    if (argument is ParsedAnnotation.ScalarArgument && argument.name == property.name && argument.value is V) {
        transform(argument.value)
    } else null
}

inline fun <reified V, R> ValidAnnotation.findArrayArgumentValue(
    property: KProperty1<out Annotation, Array<out Any>>,
    transform: (V) -> R?,
): List<R>? = arguments.firstNotNullOfOrNull { argument ->
    if (argument is ParsedAnnotation.ArrayArgument && argument.name == property.name) {
        argument.elements.map {
            val value = it as? V ?: return@firstNotNullOfOrNull null
            transform(value) ?: return@firstNotNullOfOrNull null
        }
    } else null
}
