package io.github.recrafter.lapis.extensions.kp

import io.github.diskria.poetesse.kotlin.KPTypeName
import io.github.diskria.poetesse.kotlin.KPUnit

fun KPTypeName?.orUnit(): KPTypeName =
    this ?: KPUnit
