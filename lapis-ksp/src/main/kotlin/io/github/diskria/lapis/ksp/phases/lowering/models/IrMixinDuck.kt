package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.java.JPModifier

class IrMixinDuck(
    val originatingFiles: List<KSFile>,
    val className: XClassName,
    val entries: List<IrMixinDuckEntry>,
) {
    val shadowEntries: List<IrMixinShadowEntry> get() = entries.filterIsInstance<IrMixinShadowEntry>()
}

sealed interface IrMixinDuckEntry {
    val sourceName: String
    val kinds: List<IrMixinDuckEntryKind>
}

sealed interface IrMixinDuckEntryKind {
    val name: String
    val sourceJvmName: String
    val parameters: List<IrParameter>
    val returnType: XTypeName?
}

sealed interface IrMixinDuckEntryAccessorKind : IrMixinDuckEntryKind

sealed class IrMixinDuckPropertyEntry(
    override val sourceName: String,
    val type: XTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
) : IrMixinDuckEntry {
    val getter: Getter = Getter(getterName, sourceGetterJvmName, type)
    val setter: Setter? = if (setterName != null && sourceSetterJvmName != null) {
        Setter(setterName, sourceSetterJvmName, type)
    } else null

    override val kinds: List<IrMixinDuckEntryAccessorKind> = listOfNotNull(getter, setter)

    class Getter(
        override val name: String,
        override val sourceJvmName: String,
        type: XTypeName,
    ) : IrMixinDuckEntryAccessorKind {
        override val parameters: List<IrParameter> = emptyList()
        override val returnType: XTypeName = type
    }

    class Setter(
        override val name: String,
        override val sourceJvmName: String,
        type: XTypeName,
    ) : IrMixinDuckEntryAccessorKind {
        val parameter: IrParameter = IrSetterParameter(type)
        override val parameters: List<IrParameter> = listOf(parameter)
        override val returnType: XTypeName? = null
    }
}

sealed class IrMixinDuckFunctionEntry(
    override val sourceName: String,
    override val name: String,
    override val sourceJvmName: String,
    override val parameters: List<IrParameter>,
    override val returnType: XTypeName?,
) : IrMixinDuckEntry, IrMixinDuckEntryKind {
    override val kinds: List<IrMixinDuckEntryKind> = listOf(this)
}

sealed interface IrMixinDuckExtensionEntry : IrMixinDuckEntry {
    val receiverType: XTypeName
}

class IrMixinDuckExtensionProperty(
    sourceName: String,
    type: XTypeName,
    sourceGetterJvmName: String,
    sourceSetterJvmName: String?,
    getterName: String,
    setterName: String?,
    override val receiverType: XTypeName,
) : IrMixinDuckPropertyEntry(
    sourceName,
    type,
    getterName,
    sourceGetterJvmName,
    setterName,
    sourceSetterJvmName,
), IrMixinDuckExtensionEntry

class IrMixinDuckExtensionFunction(
    sourceName: String,
    name: String,
    sourceJvmName: String,
    parameters: List<IrParameter>,
    returnType: XTypeName?,
    override val receiverType: XTypeName,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnType),
    IrMixinDuckExtensionEntry

sealed interface IrMixinShadowEntry : IrMixinDuckEntry {
    val modifiers: Set<JPModifier>
    val isStatic: Boolean get() = JPModifier.STATIC in modifiers
}

class IrMixinShadowProperty(
    sourceName: String,
    type: XTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
    val mappingName: String,
    override val modifiers: Set<JPModifier>,
    val isFinal: Boolean,
    val mixinAnnotations: List<IrMixinAnnotation>,
) : IrMixinDuckPropertyEntry(
    sourceName,
    type,
    getterName,
    sourceGetterJvmName,
    setterName,
    sourceSetterJvmName,
), IrMixinShadowEntry

class IrMixinShadowFunction(
    sourceName: String,
    name: String,
    sourceJvmName: String,
    parameters: List<IrParameter>,
    returnType: XTypeName?,
    val mappingName: String,
    val mixinAnnotations: List<IrMixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnType),
    IrMixinShadowEntry
