package io.github.diskria.lapis.extensions

fun <T> Iterable<T>.indexOfFirstOrNull(predicate: (T) -> Boolean): Int? =
    indexOfFirst(predicate).takeIf { it >= 0 }
