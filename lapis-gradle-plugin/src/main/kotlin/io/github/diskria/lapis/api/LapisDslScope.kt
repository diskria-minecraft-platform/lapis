package io.github.diskria.lapis.api

@LapisDsl
internal interface LapisDslScope

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
internal annotation class LapisDsl
