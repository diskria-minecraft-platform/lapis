package io.github.diskria.lapis.ksp.utils

import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

object JavaModifiers {
    val VISIBILITIES: EnumSet<Modifier> = EnumSet.of(PUBLIC, PROTECTED, PRIVATE)
    val FIELD_ALLOWED: EnumSet<Modifier> = EnumSet.copyOf(VISIBILITIES).apply {
        addAll(EnumSet.of(STATIC, FINAL, TRANSIENT, VOLATILE))
    }
    val METHOD_ALLOWED: EnumSet<Modifier> = EnumSet.copyOf(VISIBILITIES).apply {
        addAll(EnumSet.of(ABSTRACT, DEFAULT, STATIC, FINAL, SYNCHRONIZED, NATIVE, STRICTFP))
    }
    val ABSTRACT_ILLEGALS: EnumSet<Modifier> = EnumSet.of(PRIVATE, STATIC, FINAL, NATIVE, SYNCHRONIZED, DEFAULT)
}
