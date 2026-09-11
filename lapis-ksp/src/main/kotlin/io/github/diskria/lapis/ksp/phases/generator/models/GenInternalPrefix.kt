package io.github.diskria.lapis.ksp.phases.generator.models

import io.github.diskria.lapis.ksp.Lapis

enum class GenInternalPrefix(val value: String) {
    BUILTIN(Lapis.NAME.lowercase()),
    PARAM("param"),
    LOCAL("local"),
    SHARE("share"),
    ARGUMENT("argument"),
}
