package io.github.diskria.lapis.phases.generator.models

import io.github.diskria.lapis.Lapis

enum class GenInternalPrefix(val value: String) {
    BUILTIN(Lapis.NAME.lowercase()),
    PARAM("param"),
    LOCAL("local"),
    SHARE("share"),
    ARGUMENT("argument"),
    ACCESS("access"),
}
