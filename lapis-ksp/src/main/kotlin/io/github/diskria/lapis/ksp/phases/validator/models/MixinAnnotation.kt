package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class MixinAnnotation(private val typeClassDeclaration: KSClassDeclaration, val arguments: List<Argument>) {

    val typeClassName: XClassName get() = typeClassDeclaration.toXClassName()

    sealed interface Argument {

        val name: String

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
        class TypeValue(private val type: KSType) : Value {
            val typeName: XTypeName get() = type.toXTypeName()
        }

        class EnumValue(private val classDeclaration: KSClassDeclaration, val entryName: String) : Value {
            val className: XClassName get() = classDeclaration.toXClassName()
        }

        class AnnotationValue(val annotation: MixinAnnotation) : Value
    }

    class ScalarArgument(
        override val name: String,
        val value: Argument.Value,
    ) : Argument

    class ArrayArgument(
        override val name: String,
        val elements: List<Argument.Value>,
    ) : Argument
}
