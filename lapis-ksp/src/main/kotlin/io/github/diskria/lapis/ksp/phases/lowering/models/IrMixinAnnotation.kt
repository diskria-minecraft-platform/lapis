package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class IrMixinAnnotation(
    val className: XClassName,
    val arguments: List<IrMixinAnnotationArgument>,
)

sealed interface IrMixinAnnotationArgument {
    val name: String
}

class IrMixinAnnotationSingleArgument(
    override val name: String,
    val value: IrMixinAnnotationArgumentValue,
) : IrMixinAnnotationArgument

class IrMixinAnnotationArrayArgument(
    override val name: String,
    val values: List<IrMixinAnnotationArgumentValue>,
) : IrMixinAnnotationArgument

sealed interface IrMixinAnnotationArgumentValue
class IrMixinAnnotationBooleanArgumentValue(
    val boolean: Boolean
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationByteArgumentValue(
    val byte: Byte
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationShortArgumentValue(
    val short: Short
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationIntArgumentValue(
    val int: Int
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationLongArgumentValue(
    val long: Long
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationCharArgumentValue(
    val char: Char
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationFloatArgumentValue(
    val float: Float
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationDoubleArgumentValue(
    val double: Double
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationStringArgumentValue(
    val string: String
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationClassTypeArgumentValue(
    val typeName: XTypeName
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationEnumArgumentValue(
    val enumClassName: XClassName,
    val entryName: String,
) : IrMixinAnnotationArgumentValue

class IrMixinAnnotationEmbeddedAnnotationArgumentValue(
    val embeddedAnnotation: IrMixinAnnotation
) : IrMixinAnnotationArgumentValue
