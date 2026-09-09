package io.github.diskria.lapis.ksp.extensions.kp

import io.github.diskria.poetesse.kotlin.KPClassName

val KPClassName.qualifiedName: String
    get() = canonicalName
