package io.github.diskria.lapis.ksp.phases.validator.models.patches

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.phases.validator.models.common.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.common.SourceFile
import io.github.diskria.lapis.ksp.phases.validator.models.patches.hooks.PatchInjection

class Patch(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,
    val name: String,
    val side: Side,
    val initStrategy: InitStrategy,
    val isImplRequired: Boolean,
    val constructorParameters: List<PatchConstructorParameter>,
    val extensionSources: List<PatchExtensionSource>,
    val shadowSources: List<PatchShadowSource>,
    val injections: List<PatchInjection>,
    val targetClassDeclaration: KSClassDeclaration?,
    val mixinAnnotations: List<MixinAnnotation>,
) : SourceFile(symbol, classDeclaration)
