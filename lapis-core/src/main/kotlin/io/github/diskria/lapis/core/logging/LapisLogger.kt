package io.github.diskria.lapis.core.logging

interface LapisLogger<N> {
    fun warn(message: String, node: N? = null)
    fun error(message: String, node: N? = null)
    fun fatal(message: String, node: N? = null): Nothing {
        error(message, node)
        throw LapisException(message)
    }
}
