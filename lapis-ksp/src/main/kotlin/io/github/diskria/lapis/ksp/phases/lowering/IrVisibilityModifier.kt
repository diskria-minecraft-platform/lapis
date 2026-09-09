package io.github.diskria.lapis.ksp.phases.lowering

import io.github.diskria.poetesse.java.JPModifier
import io.github.diskria.poetesse.kotlin.KPModifier

enum class IrVisibilityModifier(val kotlin: KPModifier, val java: JPModifier) {
    PUBLIC(KPModifier.PUBLIC, JPModifier.PUBLIC),
    PRIVATE(KPModifier.PRIVATE, JPModifier.PRIVATE),
}
