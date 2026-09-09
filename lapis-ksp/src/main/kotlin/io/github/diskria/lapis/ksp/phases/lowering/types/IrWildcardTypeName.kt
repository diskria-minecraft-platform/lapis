package io.github.diskria.lapis.ksp.phases.lowering.types

import io.github.diskria.lapis.ksp.extensions.common.lapisError
import io.github.diskria.lapis.ksp.extensions.quoted
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.poetesse.java.JPObject
import io.github.diskria.poetesse.java.JPWildcardTypeName
import io.github.diskria.poetesse.kotlin.KPStar
import io.github.diskria.poetesse.kotlin.KPWildcardTypeName

class IrWildcardTypeName(override val kotlin: KPWildcardTypeName) : IrTypeName(kotlin) {

    override val java: JPWildcardTypeName by lazy {
        if (kotlin.inTypes.size > 1 || kotlin.outTypes.size > 1) {
            lapisError(
                "Wildcard type ${kotlin.toString().quoted()} with multiple bounds is not supported in Java, " +
                    "but was leaked into IR"
            )
        }
        if (kotlin == KPStar) {
            return@lazy JPWildcardTypeName.subtypeOf(JPObject)
        }
        val inBound = kotlin.inTypes.singleOrNull()?.asIrTypeName()?.java
        return@lazy if (inBound != null) {
            JPWildcardTypeName.supertypeOf(inBound)
        } else {
            val outBound = kotlin.outTypes.singleOrNull()?.asIrTypeName()?.java
            JPWildcardTypeName.subtypeOf(outBound ?: JPObject)
        }
    }
}
