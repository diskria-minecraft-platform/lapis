package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

class ParsedAnnotation(
    val classDeclaration: KSClassDeclaration?,
    val isSourceRetention: Boolean,
    val arguments: List<Argument>,
) {
    class Argument(val name: String, val isExplicit: Boolean, val values: List<Value>, val isArray: Boolean) {

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
        class ClassValue(val type: KSType) : Value
        class EnumValue(val classDeclaration: KSClassDeclaration?, val entryName: String) : Value
        class AnnotationValue(val annotation: ParsedAnnotation) : Value
    }
}
