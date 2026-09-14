package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.lapis.ksp.phases.lowering.toXTypeName
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class MixinAnnotation(classDeclaration: KSClassDeclaration, val arguments: List<Argument>) {

    val className: XClassName = classDeclaration.toXClassName()

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
        class ClassValue(type: KSType) : Value {
            val typeName: XTypeName = type.toXTypeName()
        }

        class EnumValue(classDeclaration: KSClassDeclaration, val entryName: String) : Value {
            val className: XClassName = classDeclaration.toXClassName()
        }

        class AnnotationValue(val annotation: MixinAnnotation) : Value
    }
}
