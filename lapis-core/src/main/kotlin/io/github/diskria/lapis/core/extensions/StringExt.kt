package io.github.diskria.lapis.core.extensions

fun String.quoted(): String = "\"$this\""

fun String.isSubpackageOf(parentPackage: String): Boolean =
    this == parentPackage || this.startsWith("$parentPackage.")
