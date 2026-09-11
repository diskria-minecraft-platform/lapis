package io.github.diskria.lapis.ksp.phases.validator.models.schemas

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.common.JvmClassName
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.lapis.ksp.phases.validator.models.common.SourceFile

sealed class Descriptor(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,

    val name: String,
    val mappingName: String,
    receiverType: KSType,
    val inaccessibleReceiverJvmClassName: JvmClassName?,
    val functionTypeParameters: List<FunctionTypeParameter>,
    val returnType: KSType?,
    val isStatic: Boolean,
) : SourceFile(symbol, classDeclaration) {
    val receiverTypeName: IrTypeName = receiverType.asIrTypeName()
    val returnTypeName: IrTypeName? = returnType?.asIrTypeName()
}

class FieldDescriptor(
    symbol: KSNode,

    name: String,
    mappingName: String,
    classDeclaration: KSClassDeclaration,
    receiverType: KSType,
    inaccessibleReceiverJvmClassName: JvmClassName?,
    val fieldType: KSType,
    val arrayComponentType: KSType?,
    isStatic: Boolean,
) : Descriptor(
    symbol,
    classDeclaration,
    name,
    mappingName,
    receiverType,
    inaccessibleReceiverJvmClassName,
    emptyList(),
    fieldType,
    isStatic,
) {
    val fieldTypeName: IrTypeName = fieldType.asIrTypeName()
}

sealed class InvokableDescriptor(
    symbol: KSNode,

    name: String,
    mappingName: String,
    classDeclaration: KSClassDeclaration,
    receiverType: KSType,
    inaccessibleReceiverJvmClassName: JvmClassName?,
    functionTypeParameters: List<FunctionTypeParameter>,
    returnType: KSType?,
    isStatic: Boolean,
) : Descriptor(
    symbol,
    classDeclaration,
    name,
    mappingName,
    receiverType,
    inaccessibleReceiverJvmClassName,
    functionTypeParameters,
    returnType,
    isStatic,
)

open class MethodDescriptor(
    symbol: KSNode,

    name: String,
    mappingName: String,
    classDeclaration: KSClassDeclaration,
    receiverType: KSType,
    inaccessibleReceiverJvmClassName: JvmClassName?,
    returnType: KSType?,
    functionTypeParameters: List<FunctionTypeParameter>,
    isStatic: Boolean,
) : InvokableDescriptor(
    symbol,
    name,
    mappingName,
    classDeclaration,
    receiverType,
    inaccessibleReceiverJvmClassName,
    functionTypeParameters,
    returnType,
    isStatic,
)

class ConstructorDescriptor(
    symbol: KSNode,

    name: String,
    classDeclaration: KSClassDeclaration,
    returnType: KSType,
    functionTypeParameters: List<FunctionTypeParameter>,
) : InvokableDescriptor(
    symbol, name, "", classDeclaration, returnType, null, functionTypeParameters, returnType, false,
)

class FunctionTypeParameter(val name: String?, val type: KSType) {
    val typeName: IrTypeName = type.asIrTypeName()
}
