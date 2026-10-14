package io.github.diskria.lapis.ksp.parser.polyfills

import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import io.github.diskria.lapis.ksp.parser.KspResolver
import io.github.diskria.lapis.ksp.parser.KspVariance
import io.github.diskria.lapis.ksp.parser.toName

fun KSType.fullyExpandedType(alias: KSTypeAlias, resolver: KspResolver): KSType =
    context(resolver) { directExpansionType(alias) } ?: this

context(resolver: KspResolver)
private fun KSType.directExpansionType(ksTypeAlias: KSTypeAlias, visited: Set<KSTypeAlias> = emptySet()): KSType? {
    if (ksTypeAlias in visited) return null
    if (ksTypeAlias.typeParameters.size != arguments.size) return null
    val ksTypeArguments = ksTypeAlias.typeParameters.zip(arguments) { ksTypeParameter, ksTypeArgument ->
        ksTypeParameter.name.toName() to ksTypeArgument
    }.toMap()
    val mapped = ksTypeAlias.type.resolve().mapTypeAliasArguments(ksTypeArguments, visited + ksTypeAlias)
    return mapped?.applyNullabilityFrom(this)
}

context(resolver: KspResolver)
private fun KSType.mapTypeAliasArguments(
    ksTypeArguments: Map<String, KSTypeArgument>,
    visited: Set<KSTypeAlias> = emptySet(),
): KSType? {
    val expanded = when (val ksDeclaration = declaration) {
        is KSTypeParameter -> {
            val ksTypeArgument = ksTypeArguments[ksDeclaration.name.toName()]
            when {
                ksTypeArgument != null -> ksTypeArgument.type?.resolve()?.applyNullabilityFrom(this) ?: return null
                else -> this
            }
        }

        else -> this
    }
    val substituted = if (expanded.arguments.isNotEmpty()) {
        expanded.replace(expanded.arguments.map { argument ->
            val kspVariance = argument.variance
            if (kspVariance == KspVariance.STAR) return@map argument
            val type = argument.type?.resolve() ?: return null
            val mapped = type.mapTypeAliasArguments(ksTypeArguments, visited) ?: return null
            val ksTypeArgument = (type.declaration as? KSTypeParameter)?.let { ksTypeArguments[it.name.toName()] }
            val variance = if (ksTypeArgument != null) {
                combineVariance(ksTypeArgument.variance, kspVariance) ?: return null
            } else {
                kspVariance
            }
            resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(mapped), variance)
        })
    } else {
        expanded
    }
    val ksTypeAlias = substituted.declaration as? KSTypeAlias ?: return substituted
    return substituted.directExpansionType(ksTypeAlias, visited)
}

private fun KSType.applyNullabilityFrom(abbreviation: KSType): KSType =
    if (abbreviation.isMarkedNullable && !isMarkedNullable) makeNullable()
    else this

private fun combineVariance(callSite: KspVariance, declarationSite: KspVariance): KspVariance? = when {
    callSite == declarationSite -> callSite
    callSite == KspVariance.STAR || declarationSite == KspVariance.STAR -> KspVariance.STAR
    callSite == KspVariance.INVARIANT -> declarationSite
    declarationSite == KspVariance.INVARIANT -> callSite
    else -> null
}
