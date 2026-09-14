package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class IrMixinAnnotation(val className: XClassName, val arguments: List<Argument>) {

    class Argument(val name: String, val values: List<Value>, val isArray: Boolean) {

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
        class ClassValue(val typeName: XTypeName) : Value
        class EnumValue(val className: XClassName, val entryName: String) : Value
        class AnnotationValue(val annotation: IrMixinAnnotation) : Value
    }
}
