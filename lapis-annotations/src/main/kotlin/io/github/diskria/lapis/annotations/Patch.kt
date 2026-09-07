@file:Suppress("unused", "EnumEntryName")

package io.github.diskria.lapis.annotations

import javax.lang.model.element.Modifier
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(SOURCE)
annotation class Patch(
    val target: KClass<*> = Unit::class,
    val side: Side = Side.Common,
    val initStrategy: InitStrategy = InitStrategy.Lazy,
)

enum class InitStrategy { Eager, Lazy, Volatile, Synchronized }

@Target(PROPERTY, FUNCTION)
@Retention(SOURCE)
annotation class Extension

@Target(PROPERTY, FUNCTION)
@Retention(SOURCE)
annotation class KShadow(
    vararg val modifiers: Modifier = []
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class Hook<D>(
    val at: Ats,
)

enum class Ats {
    Head, Body, Tail,

    Local,
    Instanceof,
    Return,

    Literal,

    Field, Array, Call,
}

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtConstructorHead(
    val phase: ConstructorHeadPhase,
)

enum class ConstructorHeadPhase { PreBody, PostDelegate, PostInit }

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtLocal<T>(
    val local: KLocal,
    val op: Op,
    val ordinal: IntArray = [],
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtInstanceof<T>(
    val ordinal: IntArray = [],
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtReturn(
    val ordinal: IntArray = [],
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtLiteral(
    val zero: Zero = Zero(),
    val int: Int = -1,
    val long: Long = -1L,
    val float: Float = -1f,
    val double: Double = -1.0,
    val string: String = "",
    val type: KClass<*> = Unit::class,
    val isNull: Boolean = false,
    val ordinal: IntArray = [],
)

@Target
@Retention(SOURCE)
annotation class Zero(
    vararg val conditions: ZeroCondition = [],
)

enum class ZeroCondition { `<`, `<=`, `>=`, `>` }

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtField<D>(
    val op: Op,
    val ordinal: IntArray = [],
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtArray<D>(
    val op: Op,
    val ordinal: IntArray = [],
)

@Target(FUNCTION)
@Retention(SOURCE)
annotation class AtCall<D>(
    val ordinal: IntArray = [],
)

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Origin

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Cancel

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Ordinal

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Param(
    val name: String = "",
)

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class KLocal(
    val name: String = "",
    val ordinal: Int = -1,
)

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class KShare(
    val key: String = "",
    val exported: Boolean = false,
)

object Ordinals {
    const val FIRST: Int = 0
    const val LAST: Int = Int.MAX_VALUE
}
