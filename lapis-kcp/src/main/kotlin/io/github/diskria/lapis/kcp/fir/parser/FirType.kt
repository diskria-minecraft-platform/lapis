package io.github.diskria.lapis.kcp.fir.parser

import io.github.diskria.lapis.core.parser.models.Type
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.types.*

class FirType(private val coneType: ConeKotlinType, private val session: FirSession) : Type {

    override val isNullable: Boolean get() = coneType.isMarkedNullable

    override fun isSubtypeOf(type: Type): Boolean {
        if (type !is FirType) return false
        val starProjectedCandidate = coneType.starProjection()
        val starProjectedTarget = type.coneType.starProjection()
        return starProjectedCandidate.isSubtypeOf(starProjectedTarget, session)
    }
}

private fun ConeKotlinType.starProjection(): ConeKotlinType =
    (this as? ConeSimpleKotlinType)?.replaceArgumentsWithStarProjectionsOrNull() ?: this
