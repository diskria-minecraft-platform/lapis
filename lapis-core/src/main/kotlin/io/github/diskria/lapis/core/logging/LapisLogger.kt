package io.github.diskria.lapis.core.logging

interface LapisLogger {
    fun warn(message: String)
    fun error(message: String)
    fun fatal(message: String): Nothing {
        error(message)
        throw LapisException(message)
    }
}
