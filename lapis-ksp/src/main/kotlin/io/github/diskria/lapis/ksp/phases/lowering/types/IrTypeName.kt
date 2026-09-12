package io.github.diskria.lapis.ksp.phases.lowering.types

import io.github.diskria.lapis.ksp.extensions.kp.*
import io.github.diskria.lapis.ksp.phases.lowering.*
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*

open class IrTypeName(
    open val kotlin: KPTypeName,
    val boxed: Boolean = kotlin.isNullable,
) {
    fun getJavaPrimitiveType(allowVoid: Boolean = true): JPTypeName? =
        when (makeNotNullable().kotlin) {
            KPBoolean -> JPBoolean
            KPByte -> JPByte
            KPShort -> JPShort
            KPInt -> JPInt
            KPLong -> JPLong
            KPChar -> JPChar
            KPFloat -> JPFloat
            KPDouble -> JPDouble
            KPUnit -> if (allowVoid) JPVoid else null
            else -> null
        }?.run {
            if (boxed) box()
            else this
        }

    open val java: JPTypeName by lazy {
        getJavaPrimitiveType() ?: javaArrayType ?: when (val kotlin = kotlin) {
            is KPClassName -> kotlin.asIrClassName().java
            is KPParameterizedTypeName -> kotlin.asIrParameterizedTypeName().java
            is KPWildcardTypeName -> kotlin.asIrWildcardTypeName().java
            is KPTypeVariableName -> kotlin.asIrTypeVariableName().java
            is KPFunctionalTypeName -> kotlin.asIrFunctionalTypeName().java
            else -> error("!")
        }
    }

    private val javaArrayType: JPArrayTypeName? by lazy {
        val arrayComponentType = when (val kotlin = kotlin) {
            KPBooleanArray -> JPBoolean
            KPByteArray -> JPByte
            KPShortArray -> JPShort
            KPIntArray -> JPInt
            KPLongArray -> JPLong
            KPCharArray -> JPChar
            KPFloatArray -> JPFloat
            KPDoubleArray -> JPDouble
            else -> {
                if (kotlin is KPParameterizedTypeName && kotlin.rawType == KPArray) {
                    kotlin.typeArguments.firstOrNull()?.asIrTypeName()?.java
                } else {
                    return@lazy null
                }
            }
        }
        JPArrayTypeName.of(arrayComponentType)
    }

    fun box(): IrTypeName =
        if (boxed) this
        else IrTypeName(kotlin, true)

    fun makeNotNullable(): IrTypeName =
        if (kotlin.isNullable) IrTypeName(kotlin.copy(nullable = false))
        else this

    override fun toString(): String =
        "[K=$kotlin,J=${
            java.let {
                if (boxed) "Boxed($it)"
                else it
            }
        }]"

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is IrTypeName) {
            return false
        }
        return kotlin == other.kotlin && boxed == other.boxed
    }

    override fun hashCode(): Int =
        kotlin.hashCode()
}
