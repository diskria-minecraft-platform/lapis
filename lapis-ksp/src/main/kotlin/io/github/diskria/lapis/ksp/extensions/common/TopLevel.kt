package io.github.diskria.lapis.ksp.extensions.common

fun lapisError(message: String): Nothing =
    error(
        "$message. " +
            "This is a Lapis bug. " +
            "Please report it to the issue tracker: " +
            "https://github.com/diskria-minecraft-platform/lapis/issues/"
    )
