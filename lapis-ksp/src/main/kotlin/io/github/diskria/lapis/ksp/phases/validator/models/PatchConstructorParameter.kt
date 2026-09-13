package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration

sealed interface PatchConstructorParameter
class PatchConstructorOriginParameter(val typeClassDeclaration: KSClassDeclaration) : PatchConstructorParameter
