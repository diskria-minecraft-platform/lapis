package io.github.diskria.lapis.extensions.kp

import io.github.diskria.poetesse.kotlin.KPCodeBlock

val List<KPCodeBlock>.format: String
    get() = joinToString { "%L" }
