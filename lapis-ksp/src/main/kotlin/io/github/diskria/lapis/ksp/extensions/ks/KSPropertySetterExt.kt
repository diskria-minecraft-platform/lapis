package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.symbol.KSPropertySetter
import com.google.devtools.ksp.symbol.Modifier

val KSPropertySetter.isPublic: Boolean
    get() = Modifier.PUBLIC in modifiers
