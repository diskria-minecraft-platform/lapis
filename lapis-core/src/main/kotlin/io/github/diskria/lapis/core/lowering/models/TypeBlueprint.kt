package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class TypeBlueprint(
    val inKotlin: XTypeName,
    val inJava: XTypeName = inKotlin,
    val castContext: CastContext = CastContext(null, null),
    val isReturnable: Boolean = false,
    val isFunctionType: Boolean = false,
) {
    class CastContext(val toKotlin: XTypeName?, val toJava: XTypeName?)
}

class ClassTypeBlueprint(
    val inKotlin: XClassName,
    val inJava: XClassName = inKotlin,
)
