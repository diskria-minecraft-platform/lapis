package io.github.diskria.lapis.ksp.phases.lowering.models

import io.github.diskria.lapis.ksp.extensions.kp.buildKotlinProperty
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.kotlin.KPProperty

open class IrParameter(
    val name: String,
    val typeName: IrTypeName,
)

class IrSetterParameter(
    typeName: IrTypeName,
) : IrParameter("newValue", typeName)

fun IrParameter.toKotlinConstructorProperty(
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC
): KPProperty =
    buildKotlinProperty(name, typeName, visibility = visibility) {
        initializer(name)
    }

val List<IrParameter>.format: String
    get() = joinToString { "%N" }
