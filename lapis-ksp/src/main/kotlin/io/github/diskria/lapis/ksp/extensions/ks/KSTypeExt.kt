package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.extensions.common.castOrNull

val KSType.isValid: Boolean
    get() = !isError

fun KSType.toClassDeclaration(): KSClassDeclaration? =
    declaration.castOrNull<KSClassDeclaration>()
