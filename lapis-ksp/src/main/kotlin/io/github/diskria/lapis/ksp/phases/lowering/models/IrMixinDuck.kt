package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
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
    val returnTypeName: XTypeName?
}

sealed interface IrMixinDuckEntryAccessorKind : IrMixinDuckEntryKind

sealed class IrMixinDuckPropertyEntry(
    override val sourceName: String,
    val typeName: XTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
) : IrMixinDuckEntry {
    val getter: Getter = Getter(getterName, sourceGetterJvmName, typeName)
    val setter: Setter? = if (setterName != null && sourceSetterJvmName != null) {
        Setter(setterName, sourceSetterJvmName, typeName)
    } else null

    override val kinds: List<IrMixinDuckEntryAccessorKind> = listOfNotNull(getter, setter)

    class Getter(
        override val name: String,
        override val sourceJvmName: String,
        typeName: XTypeName,
    ) : IrMixinDuckEntryAccessorKind {
        override val parameters: List<IrParameter> = emptyList()
        override val returnTypeName: XTypeName = typeName
    }

    class Setter(
        override val name: String,
        override val sourceJvmName: String,
        typeName: XTypeName,
    ) : IrMixinDuckEntryAccessorKind {
        val parameter: IrParameter = IrSetterParameter(typeName)
        override val parameters: List<IrParameter> = listOf(parameter)
        override val returnTypeName: XTypeName? = null
    }
}

sealed class IrMixinDuckFunctionEntry(
    override val sourceName: String,
    override val name: String,
    override val sourceJvmName: String,
    override val parameters: List<IrParameter>,
    override val returnTypeName: XTypeName?,
) : IrMixinDuckEntry, IrMixinDuckEntryKind {
    override val kinds: List<IrMixinDuckEntryKind> = listOf(this)
}

sealed interface IrMixinDuckExtensionEntry : IrMixinDuckEntry {
    val receiverTypeName: XTypeName
}

class IrMixinDuckExtensionProperty(
    sourceName: String,
    typeName: XTypeName,
    sourceGetterJvmName: String,
    sourceSetterJvmName: String?,
    getterName: String,
    setterName: String?,
    override val receiverTypeName: XTypeName,
) : IrMixinDuckPropertyEntry(
    sourceName,
    typeName,
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
    returnTypeName: XTypeName?,
    override val receiverTypeName: XTypeName,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinDuckExtensionEntry

sealed interface IrMixinShadowEntry : IrMixinDuckEntry {
    val modifiers: Set<JPModifier>
    val isStatic: Boolean get() = JPModifier.STATIC in modifiers
}

class IrMixinShadowProperty(
    sourceName: String,
    typeName: XTypeName,
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
    typeName,
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
    returnTypeName: XTypeName?,
    val mappingName: String,
    val mixinAnnotations: List<IrMixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinShadowEntry
