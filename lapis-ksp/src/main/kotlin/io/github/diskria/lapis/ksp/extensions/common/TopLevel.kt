package io.github.diskria.lapis.ksp.extensions.common

import io.github.diskria.lapis.ksp.Lapis

typealias Builder<T> = T.() -> Unit

fun lapisError(message: String): Nothing =
    error(
        "$message. " +
            "This is a ${Lapis.NAME} bug. " +
            "Please report it to the issue tracker: " +
            "https://github.com/diskria-minecraft-platform/${Lapis.NAME.lowercase()}/issues/"
    )
