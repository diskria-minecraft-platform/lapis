package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.core.lowering.models.MixinDuckBlueprint.Entry.Kind
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeVariableName
import io.github.diskria.poetesse.java.JPModifier

class MixinDuckBlueprint<O>(
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
            val parameters: List<FunctionParameterBlueprint>
            val returnType: TypeBlueprint?
        }
    }

    sealed interface Property : Entry {

        val type: TypeBlueprint
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
            override val returnType: TypeBlueprint,
        ) : AccessorKind {
            override val parameters: List<FunctionParameterBlueprint> = emptyList()
        }

        class Setter(
            override val name: String,
            override val sourceJvmName: String,
            type: TypeBlueprint,
        ) : AccessorKind {
            val parameter = FunctionParameterBlueprint("value", type)
            override val parameters: List<FunctionParameterBlueprint> = listOf(parameter)
            override val returnType: TypeBlueprint? = null
        }
    }

    sealed interface Function : Entry, Kind {
        override val kinds: List<Kind> get() = listOf(this)
        val typeVariables: List<XTypeVariableName>
    }

    sealed interface Extension : Entry {

        val receiverType: TypeBlueprint
        val contextParameters: List<FunctionParameterBlueprint>
        val typeVariables: List<XTypeVariableName>

        class Property(
            override val declaredName: String,
            override val type: TypeBlueprint,
            override val declaredGetterJvmName: String,
            override val declaredSetterJvmName: String?,
            override val getterName: String,
            override val setterName: String?,
            override val receiverType: TypeBlueprint,
            override val contextParameters: List<FunctionParameterBlueprint>,
            override val typeVariables: List<XTypeVariableName>,
        ) : MixinDuckBlueprint.Property,
            Extension

        class Function(
            override val declaredName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<FunctionParameterBlueprint>,
            override val contextParameters: List<FunctionParameterBlueprint>,
            override val returnType: TypeBlueprint?,
            override val receiverType: TypeBlueprint,
            override val typeVariables: List<XTypeVariableName>,
        ) : MixinDuckBlueprint.Function,
            Extension
    }

    sealed interface Shadow : Entry {

        val modifiers: List<JPModifier>
        val mappingName: String
        val annotations: List<AnnotationBlueprint>

        class Property(
            override val declaredName: String,
            override val type: TypeBlueprint,
            override val getterName: String,
            override val declaredGetterJvmName: String,
            override val setterName: String?,
            override val declaredSetterJvmName: String?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val annotations: List<AnnotationBlueprint>,
        ) : MixinDuckBlueprint.Property,
            Shadow

        class Function(
            override val declaredName: String,
            override val name: String,
            override val sourceJvmName: String,
            override val parameters: List<Parameter>,
            override val returnType: TypeBlueprint?,
            override val modifiers: List<JPModifier>,
            override val mappingName: String,
            override val annotations: List<AnnotationBlueprint>,
            override val typeVariables: List<XTypeVariableName>,
        ) : MixinDuckBlueprint.Function, Shadow {

            class Parameter(name: String, val mappingName: String, type: TypeBlueprint) : FunctionParameterBlueprint(name, type)
        }
    }
}
