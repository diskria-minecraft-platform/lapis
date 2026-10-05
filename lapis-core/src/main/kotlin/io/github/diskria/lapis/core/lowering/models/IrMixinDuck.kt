package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.core.lowering.models.IrMixinDuck.Entry.Kind
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName
import io.github.diskria.poetesse.java.JPModifier

class IrMixinDuck<O>(
    val origin: O?,
    val className: XClassName,
    val shadows: List<Shadow>,
    val extensions: List<Extension>,
) {
    sealed interface Entry {

        val declaredName: String
        val kinds: List<Kind>

        sealed interface Kind {
            val name: String
            val sourceJvmName: String
            val parameters: List<IrFunctionParameter>
            val returnType: IrType?
        }
    }

    sealed interface Property : Entry {

        val type: IrType
        val getterName: String
        val declaredGetterJvmName: String
        val setterName: String?
        val declaredSetterJvmName: String?

        override val declaredName: String

        val getter: Getter get() = Getter(getterName, declaredGetterJvmName, type)
        val setter: Setter?
            get() {
                val name = setterName
                val sourceJvmName = declaredSetterJvmName
                return if (name != null && sourceJvmName != null) {
                    Setter(name, sourceJvmName, type)
                } else null
            }

        override val kinds: List<AccessorKind> get() = listOfNotNull(getter, setter)

        sealed interface AccessorKind : Kind
        class Getter(
            override val name: String,
            override val sourceJvmName: String,
            override val returnType: IrType,
        ) : AccessorKind {
            override val parameters: List<IrFunctionParameter> = emptyList()
        }

        class Setter(
            override val name: String,
            override val sourceJvmName: String,
            type: IrType,
        ) : AccessorKind {
            val parameter = IrFunctionParameter("value", type)
            override val parameters: List<IrFunctionParameter> = listOf(parameter)
            override val returnType: IrType? = null
        }
    }

    sealed interface Function : Entry, Kind {
        override val kinds: List<Kind> get() = listOf(this)
        val typeVariables: List<XTypeVariableName>
    }

    sealed interface Extension : Entry {

        val receiverType: IrType
        val contextParameters: List<IrFunctionParameter>
        val typeVariables: List<XTypeVariableName>

        class Property(
            override val declaredName: String,
            override val type: IrType,
            override val declaredGetterJvmName: String,
            override val declaredSetterJvmName: String?,
            override val getterName: String,
            override val setterName: String?,
            override val receiverType: IrType,
            override val contextParameters: List<IrFunctionParameter>,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Property,
            Extension

        class Function(
            override val declaredName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<IrFunctionParameter>,
            override val contextParameters: List<IrFunctionParameter>,
            override val returnType: IrType?,
            override val receiverType: IrType,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Function,
            Extension
    }

    sealed interface Shadow : Entry {

        val modifiers: List<JPModifier>
        val mappingName: String
        val annotations: List<IrAnnotation>

        class Property(
            override val declaredName: String,
            override val type: IrType,
            override val getterName: String,
            override val declaredGetterJvmName: String,
            override val setterName: String?,
            override val declaredSetterJvmName: String?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val annotations: List<IrAnnotation>,
        ) : IrMixinDuck.Property,
            Shadow

        class Function(
            override val declaredName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<IrFunctionParameter>,
            override val returnType: IrType?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val annotations: List<IrAnnotation>,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Function,
            Shadow
    }
}
