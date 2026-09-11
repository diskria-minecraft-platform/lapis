@file:Suppress("unused")

package io.github.diskria.lapis.annotations

import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(SOURCE)
annotation class Class(
    val type: KClass<*> = Unit::class,
    val name: String = "",
)

@Target(CLASS)
@Retention(SOURCE)
annotation class InnerClass(
    val type: KClass<*> = Unit::class,
    val name: String = "",
    val delegate: KClass<*> = Any::class,
)

@Target(CLASS)
@Retention(SOURCE)
annotation class LocalClass(
    val index: Int,
    val name: String,
    val delegate: KClass<*> = Any::class,
)

@Target(CLASS)
@Retention(SOURCE)
annotation class AnonymousClass(
    val index: Int,
    val delegate: KClass<*>,
)

@Target(CLASS)
@Retention(SOURCE)
annotation class Field<T>(
    val static: Boolean = false,
)

@Target(CLASS)
@Retention(SOURCE)
annotation class Method<F : Function<*>>(
    val static: Boolean = false,
)

@Target(CLASS, FUNCTION)
@Retention(SOURCE)
annotation class Constructor<F : Function<*>>
