package io.github.diskria.lapis.ksp

import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.java.JPAnnotation
import io.github.diskria.poetesse.java.JPClassName
import io.github.diskria.poetesse.java.JPTypeName

class KspJavaNullabilityResolver(
    private val nullableAnnotationClassName: JPClassName?,
    private val nonNullAnnotationClassName: JPClassName?,
) : Poetesse.JavaNullabilityResolver {

    override fun setNullable(typeName: JPTypeName, nullable: Boolean): JPTypeName {
        if (nullableAnnotationClassName == null && nonNullAnnotationClassName == null) return typeName
        val targetAnnotation = if (nullable) nullableAnnotationClassName else nonNullAnnotationClassName
        val cleanAnnotations = typeName.annotations().filterNot {
            it.type() == nullableAnnotationClassName || it.type() == nonNullAnnotationClassName
        }
        val finalAnnotations = if (targetAnnotation != null) {
            cleanAnnotations + JPAnnotation.builder(targetAnnotation).build()
        } else {
            cleanAnnotations
        }
        return typeName.withoutAnnotations().annotated(finalAnnotations)
    }
}
