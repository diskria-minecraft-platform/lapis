package io.github.diskria.lapis.kcp.fir.parser

import io.github.diskria.lapis.core.parser.models.Type
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.types.*

class FirType(private val coneType: ConeKotlinType, private val session: FirSession) : Type {

    override val isNullable: Boolean get() = coneType.isMarkedNullable

    override fun isSupertypeOf(subtype: Type): Boolean {
        subtype as FirType
        val subtypeCandidate = subtype.coneType.starProjection()
        val supertypeCandidate = coneType.starProjection()
        return subtypeCandidate.isSubtypeOf(supertypeCandidate, session)
    }

    override fun toString(): String = coneType.renderReadable()
}

private fun ConeKotlinType.starProjection(): ConeKotlinType =
    (this as? ConeSimpleKotlinType)?.replaceArgumentsWithStarProjectionsOrNull() ?: this
