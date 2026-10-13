package io.github.diskria.lapis.core.extensions

fun String.quoted(): String = "\"$this\""

fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }

fun String.decapitalized(): String =
    replaceFirstChar { it.lowercase() }

fun String.isSubpackageOf(parentPackage: String): Boolean =
    this == parentPackage || this.startsWith("$parentPackage.")
