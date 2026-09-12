package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.models.common.IrMixinAnnotation
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPModifier
import io.github.diskria.poetesse.kotlin.KPTypeKind

class IrMixinDuckInterface(
    override val originatingFiles: List<KSFile>,
    override val className: IrClassName,
    val entries: List<IrMixinDuckEntry>,
) : IrKotlinClassBlueprint(KPTypeKind.INTERFACE)

sealed interface IrMixinDuckEntry {
    val sourceName: String
    val kinds: List<IrMixinDuckEntryKind>
}

sealed interface IrMixinDuckEntryKind : IrReturnable {
    val name: String
    val sourceJvmName: String
    val parameters: List<IrParameter>
    override val returnTypeName: IrTypeName?

    val hasBigArity: Boolean
        get() = parameters.size >= 23
}

sealed class IrMixinDuckPropertyEntry(
    override val sourceName: String,
    val typeName: IrTypeName,
    getterName: String,
    sourceGetterJvmName: String,
    setterName: String?,
    sourceSetterJvmName: String?,
) : IrMixinDuckEntry {
    val getter: Getter = Getter(getterName, sourceGetterJvmName, typeName)
    val setter: Setter? = if (setterName != null && sourceSetterJvmName != null) {
        Setter(setterName, sourceSetterJvmName, typeName)
    } else null

    override val kinds: List<IrMixinDuckEntryKind> = listOfNotNull(getter, setter)

    class Getter(
        override val name: String,
        override val sourceJvmName: String,
        val typeName: IrTypeName,
    ) : IrMixinDuckEntryKind {
        override val parameters: List<IrParameter> = emptyList()
        override val returnTypeName: IrTypeName = typeName
    }

    class Setter(
        override val name: String,
        override val sourceJvmName: String,
        val typeName: IrTypeName,
    ) : IrMixinDuckEntryKind {
        val parameter: IrParameter = IrSetterParameter(typeName)
        override val parameters: List<IrParameter> = listOf(parameter)
        override val returnTypeName: IrTypeName? = null
    }
}

sealed class IrMixinDuckFunctionEntry(
    override val sourceName: String,
    override val name: String,
    override val sourceJvmName: String,
    override val parameters: List<IrParameter>,
    override val returnTypeName: IrTypeName?,
) : IrMixinDuckEntry, IrMixinDuckEntryKind {
    override val kinds: List<IrMixinDuckEntryKind> = listOf(this)
}

sealed interface IrMixinDuckExtensionEntry : IrMixinDuckEntry {
    val receiverTypeName: IrTypeName
}

class IrMixinDuckExtensionProperty(
    sourceName: String,
    typeName: IrTypeName,
    sourceGetterJvmName: String,
    sourceSetterJvmName: String?,
    getterName: String,
    setterName: String?,
    override val receiverTypeName: IrTypeName,
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
    returnTypeName: IrTypeName?,
    override val receiverTypeName: IrTypeName,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinDuckExtensionEntry

sealed interface IrMixinShadowEntry : IrMixinDuckEntry {
    val modifiers: Set<JPModifier>
    val isStatic: Boolean get() = JPModifier.STATIC in modifiers
}

class IrMixinShadowProperty(
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
    returnTypeName: IrTypeName?,
    val mappingName: String,
    val mixinAnnotations: List<IrMixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : IrMixinDuckFunctionEntry(sourceName, name, sourceJvmName, parameters, returnTypeName),
    IrMixinShadowEntry
