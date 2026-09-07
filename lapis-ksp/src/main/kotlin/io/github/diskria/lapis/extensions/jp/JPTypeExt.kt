package io.github.diskria.lapis.extensions.jp

import io.github.diskria.lapis.extensions.common.Builder
import io.github.diskria.lapis.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPAnnotationBuilder
import io.github.diskria.poetesse.java.JPTypeBuilder

inline fun <reified A : Annotation> JPTypeBuilder.addAnnotation(builder: Builder<JPAnnotationBuilder> = {}) {
    addAnnotation(buildJavaAnnotation<A>(builder))
}

fun JPTypeBuilder.addSuperInterface(typeName: IrTypeName) {
    addSuperinterface(typeName.java)
}
