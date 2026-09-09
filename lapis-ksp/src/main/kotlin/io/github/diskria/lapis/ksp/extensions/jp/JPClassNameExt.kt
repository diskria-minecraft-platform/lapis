package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.poetesse.java.JPClassName

val JPClassName.binaryName: String get() = reflectionName()

val JPClassName.internalName: String get() = binaryName.replace('.', '/')

val JPClassName.qualifiedName: String get() = canonicalName()
