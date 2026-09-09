package io.github.diskria.lapis.ksp.extensions.kp

import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget
import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.poetesse.kotlin.KPAnnotationBuilder
import io.github.diskria.poetesse.kotlin.KPFileBuilder

inline fun <reified A : Annotation> KPFileBuilder.addAnnotation(
    useSiteTarget: UseSiteTarget? = null,
    builder: Builder<KPAnnotationBuilder> = {}
) {
    addAnnotation(buildKotlinAnnotation<A>(useSiteTarget, builder))
}
