package io.github.recrafter.lapis.common

import io.github.diskria.poetesse.java.*
import io.github.recrafter.lapis.extensions.jp.binaryName
import io.github.recrafter.lapis.phases.lowering.types.IrTypeName
import io.github.recrafter.lapis.phases.validator.models.schemas.*

class JvmDescriptor(private val type: JPTypeName) {

    val is64bit: Boolean = type.isPrimitive && (type == JPLong || type == JPDouble)

    val primitiveName: String? = if (type.isPrimitive) {
        when (type) {
            JPBoolean -> "Z"
            JPByte -> "B"
            JPShort -> "S"
            JPInt -> "I"
            JPLong -> "J"
            JPChar -> "C"
            JPFloat -> "F"
            JPDouble -> "D"
            else -> null
        }
    } else null

    override fun toString(): String = when (type) {
        is JPClassName -> type.objectName
        is JPArrayTypeName -> "[" + type.componentType().jvmDescriptor
        is JPParameterizedTypeName -> type.rawType().objectName
        is JPTypeVariableName -> error("!")
        is JPWildcardTypeName -> error("!")
        else -> primitiveName ?: VOID_NAME
    }

    private val JPClassName.objectName: String
        get() = JvmClassName.of(binaryName).descriptor

    companion object {
        fun signatureOf(parameterTypeNames: List<IrTypeName>, returnTypeName: IrTypeName?): String =
            parameterTypeNames.joinToString(prefix = "(", separator = "", postfix = ")") {
                it.jvmDescriptor.toString()
            } + (returnTypeName?.jvmDescriptor ?: VOID_NAME)
    }
}

val IrTypeName.jvmDescriptor: JvmDescriptor
    get() = java.jvmDescriptor

fun Descriptor.getMixinReference(isTarget: Boolean = false): String =
    when (this) {
        is FieldDescriptor -> buildString {
            if (isTarget) {
                append(inaccessibleReceiverJvmClassName?.descriptor ?: receiverTypeName.jvmDescriptor)
            }
            append(mappingName)
            append(":")
            append(fieldTypeName.jvmDescriptor)
        }

        is MethodDescriptor -> buildString {
            if (isTarget) {
                append(inaccessibleReceiverJvmClassName?.descriptor ?: receiverTypeName.jvmDescriptor)
            }
            append(mappingName)
            append(
                JvmDescriptor.signatureOf(
                    functionTypeParameters.map { it.typeName },
                    returnTypeName
                )
            )
        }

        is ConstructorDescriptor -> buildString {
            if (!isTarget) {
                append(CONSTRUCTOR_NAME)
            }
            append(
                JvmDescriptor.signatureOf(
                    functionTypeParameters.map { it.typeName },
                    if (isTarget) returnTypeName else null
                )
            )
        }
    }

val InvokableDescriptor.binaryName: String
    get() = when (this) {
        is ConstructorDescriptor -> CONSTRUCTOR_NAME
        is MethodDescriptor -> mappingName
    }

private const val CONSTRUCTOR_NAME: String = "<init>"
private const val VOID_NAME: String = "V"

private val JPTypeName.jvmDescriptor: JvmDescriptor
    get() = JvmDescriptor(this)
