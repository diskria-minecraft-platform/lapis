package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.poetesse.java.JPAnnotationBuilder
import io.github.diskria.poetesse.java.JPFieldBuilder

inline fun <reified A : Annotation> JPFieldBuilder.addAnnotation(builder: Builder<JPAnnotationBuilder> = {}) {
    addAnnotation(buildJavaAnnotation<A>(builder))
}
