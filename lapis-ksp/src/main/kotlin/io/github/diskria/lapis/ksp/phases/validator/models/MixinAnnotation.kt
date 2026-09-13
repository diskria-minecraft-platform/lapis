package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.lapis.ksp.phases.lowering.toXTypeName
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class MixinAnnotation(typeClassDeclaration: KSClassDeclaration, val arguments: List<MixinAnnotationArgument>) {
    val className: XClassName = typeClassDeclaration.toXClassName()
}

sealed interface MixinAnnotationArgument {
    val name: String
}

class MixinAnnotationSingleArgument(
    override val name: String,
    val value: MixinAnnotationArgumentValue,
) : MixinAnnotationArgument

class MixinAnnotationArrayArgument(
    override val name: String,
    val values: List<MixinAnnotationArgumentValue>,
) : MixinAnnotationArgument

sealed interface MixinAnnotationArgumentValue
class MixinAnnotationBooleanArgumentValue(val boolean: Boolean) : MixinAnnotationArgumentValue
class MixinAnnotationByteArgumentValue(val byte: Byte) : MixinAnnotationArgumentValue
class MixinAnnotationShortArgumentValue(val short: Short) : MixinAnnotationArgumentValue
class MixinAnnotationIntArgumentValue(val int: Int) : MixinAnnotationArgumentValue
class MixinAnnotationLongArgumentValue(val long: Long) : MixinAnnotationArgumentValue
class MixinAnnotationCharArgumentValue(val char: Char) : MixinAnnotationArgumentValue
class MixinAnnotationFloatArgumentValue(val float: Float) : MixinAnnotationArgumentValue
class MixinAnnotationDoubleArgumentValue(val double: Double) : MixinAnnotationArgumentValue
class MixinAnnotationStringArgumentValue(val string: String) : MixinAnnotationArgumentValue
class MixinAnnotationClassTypeArgumentValue(type: KSType) : MixinAnnotationArgumentValue {
    val typeName: XTypeName = type.toXTypeName()
}

class MixinAnnotationEnumArgumentValue(
    enumClassDeclaration: KSClassDeclaration,
    val entryName: String,
) : MixinAnnotationArgumentValue {
    val enumClassName: XClassName = enumClassDeclaration.toXClassName()
}

class MixinAnnotationEmbeddedAnnotationArgumentValue(
    val embeddedAnnotation: MixinAnnotation
) : MixinAnnotationArgumentValue
