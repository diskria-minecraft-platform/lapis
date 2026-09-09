package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.poetesse.java.JPTypeName
import io.github.diskria.poetesse.java.JPVoid

fun JPTypeName?.orVoid(): JPTypeName =
    this ?: JPVoid
