package io.github.diskria.lapis.core.parser.models

interface Type {
    fun isSubtypeOf(type: Type): Boolean
}
