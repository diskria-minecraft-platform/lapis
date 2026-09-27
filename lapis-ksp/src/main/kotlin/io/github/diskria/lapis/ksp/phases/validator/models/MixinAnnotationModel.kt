package io.github.diskria.lapis.ksp.phases.validator.models

class MixinAnnotationModel(val typeClassDeclaration: ClassDeclarationModel, val arguments: List<Argument>) {

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
        class ClassValue(val classDeclaration: ClassDeclarationModel) : Value
        class EnumValue(val classDeclaration: ClassDeclarationModel, val name: String) : Value
        class AnnotationValue(val annotation: MixinAnnotationModel) : Value
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
