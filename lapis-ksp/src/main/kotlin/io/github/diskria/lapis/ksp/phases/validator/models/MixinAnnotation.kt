package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.lapis.ksp.phases.lowering.toXTypeName
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class MixinAnnotation(classDeclaration: KSClassDeclaration, val arguments: List<Argument>) {

    val className: XClassName = classDeclaration.toXClassName()

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
        class TypeValue(type: KSType) : Value {
            val typeName: XTypeName = type.toXTypeName()
        }

        class EnumValue(classDeclaration: KSClassDeclaration, val entryName: String) : Value {
            val className: XClassName = classDeclaration.toXClassName()
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
