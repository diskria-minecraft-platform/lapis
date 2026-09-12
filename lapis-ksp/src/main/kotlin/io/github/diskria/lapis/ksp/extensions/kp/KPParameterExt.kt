package io.github.diskria.lapis.ksp.extensions.kp

import io.github.diskria.poetesse.kotlin.KPParameter

val List<KPParameter>.format: String
    get() = joinToString { "%N" }
