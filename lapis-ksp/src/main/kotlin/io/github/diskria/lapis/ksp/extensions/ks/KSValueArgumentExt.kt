package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.Origin

val KSValueArgument.isExplicit: Boolean
    get() = origin != Origin.SYNTHETIC
