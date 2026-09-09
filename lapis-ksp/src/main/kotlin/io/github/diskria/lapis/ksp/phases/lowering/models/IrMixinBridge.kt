package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPModifier
import io.github.diskria.poetesse.kotlin.KPTypeKind

class IrMixinBridge(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    val entries: List<IrMixinBridgeEntry>,
) : IrKotlinClassBlueprint(KPTypeKind.INTERFACE)

sealed interface IrMixinBridgeEntry {
    val sourceName: String
    val kinds: List<IrMixinBridgeEntryKind>
}

sealed interface IrMixinBridgeEntryKind : IrReturnable {
    val name: String
    val sourceJvmName: String
    val parameters: List<IrParameter>
    override val returnTypeName: IrTypeName?

    val hasBigArity: Boolean
        get() = parameters.size >= 23
}

sealed class IrMixinBridgeProperty(
    override val sourceName: String,
    val typeName: IrTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
) : IrMixinBridgeEntry {
    val getter: Getter = Getter(getterName, sourceGetterJvmName, typeName)
    val setter: Setter? = if (setterName != null && sourceSetterJvmName != null) {
        Setter(setterName, sourceSetterJvmName, typeName)
    } else null

    override val kinds: List<IrMixinBridgeEntryKind> = listOfNotNull(getter, setter)

    class Getter(
        override val name: String,
        override val sourceJvmName: String,
        val typeName: IrTypeName,
    ) : IrMixinBridgeEntryKind {
        override val parameters: List<IrParameter> = emptyList()
        override val returnTypeName: IrTypeName = typeName
    }

    class Setter(
        override val name: String,
        override val sourceJvmName: String,
        val typeName: IrTypeName,
    ) : IrMixinBridgeEntryKind {
        val parameter: IrParameter = IrSetterParameter(typeName)
        override val parameters: List<IrParameter> = listOf(parameter)
        override val returnTypeName: IrTypeName? = null
    }
}

sealed class IrMixinBridgeFunctionEntry(
    override val sourceName: String,
    override val name: String,
    override val sourceJvmName: String,
    override val parameters: List<IrParameter>,
    override val returnTypeName: IrTypeName?,
) : IrMixinBridgeEntry, IrMixinBridgeEntryKind {
    override val kinds: List<IrMixinBridgeEntryKind> = listOf(this)
}

sealed interface IrMixinBridgeExtensionEntry : IrMixinBridgeEntry {
    val receiverTypeName: IrTypeName
}

class IrMixinBridgeExtensionProperty(
    sourceName: String,
    typeName: IrTypeName,
    sourceGetterJvmName: String,
    sourceSetterJvmName: String?,
    getterName: String,
    setterName: String?,
    override val receiverTypeName: IrTypeName,
) : IrMixinBridgeProperty(
    sourceName,
    typeName,
    getterName,
    sourceGetterJvmName,
    setterName,
    sourceSetterJvmName,
), IrMixinBridgeExtensionEntry

class IrMixinBridgeExtensionFunction(
    sourceName: String,
    name: String,
    sourceJvmName: String,
    parameters: List<IrParameter>,
    returnTypeName: IrTypeName?,
    override val receiverTypeName: IrTypeName,
) : IrMixinBridgeFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinBridgeExtensionEntry

sealed interface IrMixinBridgeShadowEntry : IrMixinBridgeEntry {
    val modifiers: Set<JPModifier>
    val isStatic: Boolean get() = JPModifier.STATIC in modifiers
}

class IrMixinBridgeShadow(
    sourceName: String,
    typeName: IrTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
    val mappingName: String,
    override val modifiers: Set<JPModifier>,
    val isFinal: Boolean,
    val mixinAnnotations: List<IrMixinAnnotation>,
) : IrMixinBridgeProperty(
    sourceName,
    typeName,
    getterName,
    sourceGetterJvmName,
    setterName,
    sourceSetterJvmName,
), IrMixinBridgeShadowEntry

class IrMixinBridgeShadowFunction(
    sourceName: String,
    name: String,
    sourceJvmName: String,
    parameters: List<IrParameter>,
    returnTypeName: IrTypeName?,
    val mappingName: String,
    val mixinAnnotations: List<IrMixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : IrMixinBridgeFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinBridgeShadowEntry
