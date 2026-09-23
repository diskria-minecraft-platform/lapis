package io.github.diskria.lapis.annotations

import javax.lang.model.element.Modifier
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(SOURCE)
annotation class KMixin(
    val target: KClass<*> = Any::class,
    val side: Side,
    val initStrategy: InitStrategy = InitStrategy.Lazy,
)

enum class Side { Common, Client, Server }
enum class InitStrategy { Eager, Lazy, Volatile, Synchronized }

@Target(VALUE_PARAMETER)
@Retention(SOURCE)
annotation class Origin

@Target(PROPERTY, FUNCTION)
@Retention(SOURCE)
annotation class Extension

@Target(PROPERTY, FUNCTION)
@Retention(SOURCE)
annotation class KShadow(vararg val modifiers: Modifier = [])

@Target(FUNCTION, PROPERTY)
@Retention(SOURCE)
annotation class MappingName(val name: String)
