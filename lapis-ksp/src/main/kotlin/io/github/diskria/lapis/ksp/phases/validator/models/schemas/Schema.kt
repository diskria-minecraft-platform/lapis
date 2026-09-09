package io.github.diskria.lapis.ksp.phases.validator.models.schemas

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.common.JvmClassName
import io.github.diskria.lapis.ksp.extensions.ks.starProjectedType
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.lapis.ksp.phases.validator.models.common.SourceFile

class Schema(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,

    val originJvmClassName: JvmClassName,
    val originClassDeclaration: KSClassDeclaration,
    val side: Side,

    val isAccessible: Boolean,

    val accessRequest: AccessRequest?,
    val descriptors: List<Descriptor>,
) : SourceFile(symbol, classDeclaration) {
    val originTypeName: IrTypeName = originClassDeclaration.starProjectedType.asIrTypeName()
}
