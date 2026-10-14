package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.poetesse.interop.XTypeName

class TypeBlueprint(
    val inKotlin: XTypeName,
    val inJava: XTypeName,
    val castContext: CastContext,
    val isReturnable: Boolean,
    val isFunctionType: Boolean = false,
) {
    class CastContext(val toKotlin: XTypeName?, val toJava: XTypeName?)
}
