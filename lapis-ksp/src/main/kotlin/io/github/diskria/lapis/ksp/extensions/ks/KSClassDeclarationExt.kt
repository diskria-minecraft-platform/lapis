package io.github.diskria.lapis.ksp.extensions.ks

import com.google.devtools.ksp.*
import com.google.devtools.ksp.symbol.*

val KSClassDeclaration.isClass: Boolean
    get() = classKind == ClassKind.CLASS

val KSClassDeclaration.isObject: Boolean
    get() = classKind == ClassKind.OBJECT

val KSClassDeclaration.isExplicitlyOpen: Boolean
    get() = Modifier.OPEN in modifiers

val KSClassDeclaration.isExplicitlyAbstract: Boolean
    get() = Modifier.ABSTRACT in modifiers

val KSClassDeclaration.isSealed: Boolean
    get() = Modifier.SEALED in modifiers

val KSClassDeclaration.isValid: Boolean
    get() = validate(enableNewFeatures = true)

val KSClassDeclaration.bodyPropertyDeclarations: Sequence<KSPropertyDeclaration>
    get() {
        val constructorPropertyNames = constructorDeclarations.flatMap {
            it.constructorProperties.mapNotNull { property -> property.name?.asString() }
        }
        return getDeclaredProperties().filter { it.name !in constructorPropertyNames }
    }

val KSClassDeclaration.constructorDeclarations: Sequence<KSFunctionDeclaration>
    get() = getConstructors()

val KSClassDeclaration.functionDeclarations: Sequence<KSFunctionDeclaration>
    get() = getDeclaredFunctions().filter { !it.isConstructor() }

val KSClassDeclaration.innerClassDeclarations: Sequence<KSClassDeclaration>
    get() = declarations.filterIsInstance<KSClassDeclaration>()

val KSClassDeclaration.companionObjectClassDeclarations: Sequence<KSClassDeclaration>
    get() = innerClassDeclarations.filter { it.isCompanionObject }
