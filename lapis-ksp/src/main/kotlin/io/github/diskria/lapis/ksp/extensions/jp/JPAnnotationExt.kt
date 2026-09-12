package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.phases.generator.builders.IrJavaCodeBlock
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPAnnotationBuilder
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

@JvmName("setStringArrayArgumentValue")
inline fun <reified A : Annotation> JPAnnotationBuilder.setArgumentValue(
    property: KProperty1<A, Array<out String>>,
    strings: List<String>,
) {
    setArrayArgumentValue(property, strings, "%S") { strings.forEach { +it } }
}

@JvmName("setClassArrayArgumentValue")
inline fun <reified A : Annotation> JPAnnotationBuilder.setArgumentValue(
    property: KProperty1<A, Array<KClass<*>>>,
    types: List<IrTypeName>,
) {
    setArrayArgumentValue(property, types, "%T.class") { types.forEach { +it } }
}

inline fun <reified A : Annotation> JPAnnotationBuilder.setArrayArgumentValue(
    property: KProperty1<A, *>,
    array: List<*>,
    placeholder: String,
    noinline argumentsBuilder: Builder<IrJavaCodeBlock.Arguments> = {}
) {
    addMember(
        property.name,
        buildJavaCodeBlock(array.joinToString(prefix = "{", postfix = "}") { placeholder }, argumentsBuilder)
    )
}
