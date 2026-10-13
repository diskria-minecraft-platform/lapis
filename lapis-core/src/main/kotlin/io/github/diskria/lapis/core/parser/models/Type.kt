package io.github.diskria.lapis.core.parser.models

interface Type {
    val isNullable: Boolean

    fun isSupertypeOf(subtype: Type): Boolean
}
