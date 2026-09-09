package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Op
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.common.JvmClassName
import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPTypeKind
import io.github.diskria.poetesse.kotlin.KPTypeKind

class IrResult(
    val schemas: List<IrSchema>,
    val patches: List<IrPatch>,
)

sealed class IrSourceFile(val className: IrClassName)

class IrSchema(
    className: IrClassName,
    val descriptors: List<IrDescriptor>,
    val tweakAccessor: IrTweakAccessor?,
    val mixinAccessor: IrMixinAccessor?,
) : IrSourceFile(className)

abstract class IrMixinRelatedBlueprint(typeKind: JPTypeKind) : IrJavaFileBlueprint(typeKind) {
    abstract val side: Side
}

sealed interface IrAccessor

class IrMixinAccessor(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    override val side: Side,
    val isAccessibleSchema: Boolean,
    val targetInternalName: String,
    val instanceTypeName: IrTypeName,
    val members: List<IrMixinAccessorMember>,
) : IrMixinRelatedBlueprint(JPTypeKind.INTERFACE), IrAccessor

sealed class IrMixinAccessorMember(
    val name: String,
    val isStatic: Boolean,
    val descriptorClassName: IrClassName,
)

class IrMixinAccessorFieldMember(
    name: String,
    val mappingName: String,
    val typeName: IrTypeName,
    isStatic: Boolean,
    val removeFinal: Boolean,
    val ops: List<Op>,
    descriptorClassName: IrClassName,
) : IrMixinAccessorMember(name, isStatic, descriptorClassName)

class IrMixinAccessorMethodMember(
    name: String,
    val mappingName: String,
    val parameters: List<IrParameter>,
    override val returnTypeName: IrTypeName?,
    val isConstructor: Boolean,
    isStatic: Boolean,
    descriptorClassName: IrClassName,
) : IrMixinAccessorMember(name, isStatic, descriptorClassName), IrReturnable

class IrTweakAccessor(
    val originatingFiles: List<KSFile>,

    val ownerJvmClassName: JvmClassName,
    val entries: List<IrTweakAccessorEntry>,
) : IrAccessor

class IrPatch(
    className: IrClassName,
    val constructorArguments: List<IrPatchConstructorArgument>,
    val impl: IrPatchImpl?,
    val mixin: IrMixin,
) : IrSourceFile(className)

class IrMixin(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    override val side: Side,
    val injections: List<IrInjection>,
    val bridge: IrMixinBridge?,
    val targetInternalName: String?,
    val mixinAnnotations: List<IrMixinAnnotation>,
) : IrMixinRelatedBlueprint(JPTypeKind.CLASS)

sealed interface IrPatchConstructorArgument
class IrPatchConstructorOriginArgument(val className: IrClassName) : IrPatchConstructorArgument

class IrPatchImpl(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    val constructorParameters: List<IrPatchImplConstructorParameter>,
    val initStrategy: InitStrategy,
) : IrKotlinClassBlueprint(KPTypeKind.CLASS)

sealed interface IrPatchImplConstructorParameter
class IrPatchImplConstructorInstanceParameter(val className: IrClassName) : IrPatchImplConstructorParameter
object IrPatchImplConstructorInternalBridgeParameter : IrPatchImplConstructorParameter
