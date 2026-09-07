package io.github.diskria.lapis.extensions.jp

import io.github.diskria.lapis.extensions.common.Builder
import io.github.diskria.poetesse.java.JPAnnotationBuilder
import io.github.diskria.poetesse.java.JPParameterBuilder

inline fun <reified A : Annotation> JPParameterBuilder.addAnnotation(builder: Builder<JPAnnotationBuilder> = {}) {
    addAnnotation(buildJavaAnnotation<A>(builder))
}
