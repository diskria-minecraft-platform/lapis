package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.core.parser.models.Type

class KspType(val ksType: KSType) : Type {
    override fun isSubtypeOf(type: Type): Boolean {
        if (type !is KspType) return false
        val candidate = ksType.starProjection()
        val target = type.ksType.starProjection()
        return target.isAssignableFrom(candidate)
    }
}
