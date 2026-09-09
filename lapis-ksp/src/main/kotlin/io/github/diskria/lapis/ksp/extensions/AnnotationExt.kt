package io.github.diskria.lapis.ksp.extensions

import kotlin.reflect.KClass

val KClass<out Annotation>.atName: String
    get() = "@$simpleName"
