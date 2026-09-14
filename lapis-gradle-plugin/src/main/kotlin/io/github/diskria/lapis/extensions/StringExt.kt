package io.github.diskria.lapis.extensions

fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }

fun String.decapitalized(): String =
    replaceFirstChar { it.lowercase() }

fun String.doubleQuoted(): String =
    "\"$this\""
