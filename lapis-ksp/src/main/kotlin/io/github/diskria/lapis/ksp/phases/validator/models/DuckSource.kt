package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.toXTypeName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.java.JPModifier

sealed interface DuckSource
sealed class DuckSourceProperty(
    val name: String,
    val getterJvmName: String,
    val setterJvmName: String?,
    type: KSType,
) : DuckSource {
    val typeName: XTypeName = type.toXTypeName()
}

sealed class DuckSourceFunction(
    val name: String,
    val jvmName: String,
    val parameters: List<FunctionParameter>,
    returnType: KSType?,
) : DuckSource {
    val returnTypeName: XTypeName? = returnType?.toXTypeName()
}

sealed interface PatchExtensionSource {
    val receiverClassDeclaration: KSClassDeclaration
}

class ExtensionProperty(
    name: String,
    getterJvmName: String,
    setterJvmName: String?,
    type: KSType,
    override val receiverClassDeclaration: KSClassDeclaration,
) : DuckSourceProperty(name, getterJvmName, setterJvmName, type), PatchExtensionSource

class ExtensionFunction(
    name: String,
    jvmName: String,
    parameters: List<FunctionParameter>,
    returnType: KSType?,
    override val receiverClassDeclaration: KSClassDeclaration,
) : DuckSourceFunction(name, jvmName, parameters, returnType), PatchExtensionSource

sealed interface PatchShadowSource {
    val modifiers: Set<JPModifier>
}

class ShadowProperty(
    name: String,
    getterJvmName: String,
    setterJvmName: String?,
    type: KSType,
    val mappingName: String,
    val mixinAnnotations: List<MixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : DuckSourceProperty(name, getterJvmName, setterJvmName, type), PatchShadowSource

class ShadowFunction(
    name: String,
    jvmName: String,
    parameters: List<FunctionParameter>,
    returnType: KSType?,
    val mappingName: String,
    val mixinAnnotations: List<MixinAnnotation>,
    override val modifiers: Set<JPModifier>,
) : DuckSourceFunction(name, jvmName, parameters, returnType), PatchShadowSource

class FunctionParameter(val name: String, private val type: KSType) {
    fun asIrParameter(): IrParameter = IrParameter(name, type.toXTypeName())
}
