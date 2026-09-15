package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.*
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

val KSClassDeclaration.isValid: Boolean
    get() = validate(enableNewFeatures = true)

val KSClassDeclaration.bodyPropertyDeclarations: Sequence<KSPropertyDeclaration>
    get() {
        val constructorPropertyNames = constructorDeclarations.flatMap { decl ->
            decl.parameters.filter { it.isVal || it.isVar }.mapNotNull { property -> property.name?.asString() }
        }
        return getDeclaredProperties().filter { it.simpleName.asString() !in constructorPropertyNames }
    }

val KSClassDeclaration.constructorDeclarations: Sequence<KSFunctionDeclaration>
    get() = getConstructors()

val KSClassDeclaration.functionDeclarations: Sequence<KSFunctionDeclaration>
    get() = getDeclaredFunctions().filter { !it.isConstructor() }
