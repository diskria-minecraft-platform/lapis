package io.github.diskria.lapis.ksp.extensions

fun String.capitalize(): String =
    replaceFirstChar {
        if (it.isLowerCase()) it.titlecase()
        else it.toString()
    }

fun String.quoted(): String =
    "'$this'"
