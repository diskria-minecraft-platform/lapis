package io.github.diskria.lapis.ksp.extensions

fun String.quoted(): String = "\"$this\""

fun String.isSubpackageOf(parentPackage: String): Boolean =
    this == parentPackage || this.startsWith("$parentPackage.")
