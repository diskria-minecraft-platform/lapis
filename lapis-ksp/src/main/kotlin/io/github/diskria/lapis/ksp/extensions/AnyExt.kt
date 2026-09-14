package io.github.diskria.lapis.ksp.extensions

import kotlin.reflect.KClass

inline fun <reified T : Any> Any.castOrNull(): T? =
    this as? T

inline fun <reified T : Any> KClass<T>.requireQualifiedName(): String =
    qualifiedName ?: internalError("Cannot resolve qualified name for '${T::class.simpleName}'.")
