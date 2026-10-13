package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.core.parser.models.Type

class KspType(private val ksType: KSType) : Type {

    override val isNullable: Boolean get() = ksType.isMarkedNullable

    override fun isSubtypeOf(type: Type): Boolean {
        if (type !is KspType) return false
        val starProjectedCandidate = ksType.starProjection()
        val starProjectedTarget = type.ksType.starProjection()
        return starProjectedTarget.isAssignableFrom(starProjectedCandidate)
    }
}
