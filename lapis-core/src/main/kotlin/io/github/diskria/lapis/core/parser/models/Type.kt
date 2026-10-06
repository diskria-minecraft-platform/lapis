package io.github.diskria.lapis.core.parser.models

interface Type {
    val isNullable: Boolean

    fun isSubtypeOf(type: Type): Boolean
}
