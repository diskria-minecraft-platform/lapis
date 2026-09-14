package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

fun KSType.toClassDeclaration(): KSClassDeclaration? =
    declaration as? KSClassDeclaration
