package io.github.diskria.lapis.annotations

import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*

@Target(CLASS, FUNCTION, PROPERTY)
@Retention(SOURCE)
annotation class MappingName(
    val name: String,
)
