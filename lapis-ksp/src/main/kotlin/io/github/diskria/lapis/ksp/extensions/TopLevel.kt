package io.github.diskria.lapis.ksp.extensions

fun internalError(message: String): Nothing =
    error(buildString {
        appendLine(message)
        appendLine("This is a Lapis KSP internal error. Please report it to the issue tracker:")
        append("https://github.com/diskria-minecraft-platform/lapis/issues/")
    })

inline fun <reified T : Any> qualifiedNameOf(): String =
    T::class.qualifiedName ?: internalError("Failed to resolve qualified name for '${T::class.simpleName}'.")
