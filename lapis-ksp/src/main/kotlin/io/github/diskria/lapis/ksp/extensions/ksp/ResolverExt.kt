package io.github.diskria.lapis.ksp.extensions.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import io.github.diskria.lapis.ksp.extensions.requireQualifiedName

inline fun <reified A : Annotation> Resolver.getSymbolsAnnotatedWith(): Sequence<KSAnnotated> =
    getSymbolsWithAnnotation(requireQualifiedName<A>())
