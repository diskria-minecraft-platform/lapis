package io.github.diskria.lapis.ksp.parser

import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.core.parser.models.Type

class KspType(private val ksType: KSType) : Type {

    override val isNullable: Boolean get() = ksType.isMarkedNullable

    override fun isSupertypeOf(subtype: Type): Boolean {
        if (subtype !is KspType) return false
        val subtypeCandidate = subtype.ksType.starProjection()
        val supertypeCandidate = ksType.starProjection()
        return supertypeCandidate.isAssignableFrom(subtypeCandidate)
    }
}
