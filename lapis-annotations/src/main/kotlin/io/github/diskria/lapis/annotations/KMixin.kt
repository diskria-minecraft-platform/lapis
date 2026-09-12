@file:Suppress("unused", "EnumEntryName")

package io.github.diskria.lapis.annotations

import javax.lang.model.element.Modifier
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(SOURCE)
annotation class KMixin(
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

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Origin
