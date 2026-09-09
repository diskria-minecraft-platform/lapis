package io.github.diskria.lapis.ksp.extensions.kp

import io.github.diskria.poetesse.kotlin.KPTypeName
import io.github.diskria.poetesse.kotlin.KPUnit

fun KPTypeName?.orUnit(): KPTypeName =
    this ?: KPUnit
