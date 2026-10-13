package io.github.diskria.lapis.core.extensions

import java.io.File

// TODO: Replace with an abstraction layer to support Gradle, KSP, Compiler, and IDE environments.
fun lapisLog(message: String) {
    File(System.getProperty("user.home"), "lapis.log").appendText("$message\n")
}

fun lapisError(message: String): Nothing =
    error(buildString {
        appendLine(message)
        appendLine("This is a Lapis internal error. Please report it to the issue tracker:")
        append("https://github.com/diskria-minecraft-platform/lapis/issues/")
    })

inline fun <reified T : Any> qualifiedNameOf(): String =
    T::class.qualifiedName ?: lapisError("Failed to resolve qualified name for '${T::class}'.")

inline fun <reified T : Any> simpleNameOf(): String =
    T::class.simpleName ?: lapisError("Failed to resolve simple name for '${T::class}'.")
