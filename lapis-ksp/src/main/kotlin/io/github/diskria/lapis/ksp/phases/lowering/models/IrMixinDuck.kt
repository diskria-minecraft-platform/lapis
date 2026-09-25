package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.models.IrMixinDuck.Entry.Kind
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName
import io.github.diskria.poetesse.java.JPModifier

class IrMixinDuck(
    val originatingFile: KSFile?,
    val className: XClassName,
    val typeVariables: List<XTypeVariableName>,
    val shadows: List<Shadow>,
    val extensions: List<Extension>,
) {
    sealed interface Entry {

        val sourceName: String
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
        val sourceGetterJvmName: String
        val setterName: String?
        val sourceSetterJvmName: String?

        override val sourceName: String

        val getter: Getter get() = Getter(getterName, sourceGetterJvmName, type)
        val setter: Setter?
            get() {
                val name = setterName
                val sourceJvmName = sourceSetterJvmName
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
            val parameter = IrFunctionParameter("newValue", type)
            override val parameters: List<IrFunctionParameter> = listOf(parameter)
            override val returnType: IrType? = null
        }
    }

    sealed interface Function : Entry, Kind {
        override val kinds: List<Kind> get() = listOf(this)
    }

    sealed interface Extension : Entry {

        val receiverTargetTypeCast: IrTargetSubtypeCast
        val typeVariables: List<XTypeVariableName>

        class Property(
            override val sourceName: String,
            override val type: IrType,
            override val sourceGetterJvmName: String,
            override val sourceSetterJvmName: String?,
            override val getterName: String,
            override val setterName: String?,
            override val receiverTargetTypeCast: IrTargetSubtypeCast,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Property,
            Extension

        class Function(
            override val sourceName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<IrFunctionParameter>,
            override val returnType: IrType?,
            override val receiverTargetTypeCast: IrTargetSubtypeCast,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Function,
            Extension
    }

    sealed interface Shadow : Entry {

        val modifiers: List<JPModifier>
        val typeVariables: List<XTypeVariableName>
        val mappingName: String
        val mixinAnnotations: List<IrMixinAnnotation>

        class Property(
            override val sourceName: String,
            override val type: IrType,
            override val getterName: String,
            override val sourceGetterJvmName: String,
            override val setterName: String?,
            override val sourceSetterJvmName: String?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<IrMixinAnnotation>,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Property,
            Shadow

        class Function(
            override val sourceName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<IrFunctionParameter>,
            override val returnType: IrType?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<IrMixinAnnotation>,
            override val typeVariables: List<XTypeVariableName>,
        ) : IrMixinDuck.Function,
            Shadow
    }
}
