package io.github.diskria.lapis.annotations

import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*

@Target(FUNCTION, PROPERTY)
@Retention(SOURCE)
annotation class MappingName(
    val name: String,
)
