package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.annotations.InitStrategy

class Patch(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,
    val name: String,
    val env: Env,
    val initStrategy: InitStrategy,
    val isImplRequired: Boolean,
    val constructorParameters: List<PatchConstructorParameter>,
    val extensionSources: List<PatchExtensionSource>,
    val shadowSources: List<PatchShadowSource>,
    val injections: List<PatchInjection>,
    val targetClassDeclaration: KSClassDeclaration?,
    val mixinAnnotations: List<MixinAnnotation>,
) : SourceFile(symbol, classDeclaration)
