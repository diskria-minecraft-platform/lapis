package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import java.util.*
import javax.lang.model.element.Modifier

class KMixinModel(
    val containingFile: KSFile?,
    val classDeclaration: KSClassDeclaration,
    val name: String,
    val side: Side,
    val initStrategy: InitStrategy,
    val classKind: ClassKind,
    val shadowSources: List<Shadow>,
    val extensionSources: List<Extension>,
    val injections: List<Injection>,
    val companionObject: CompanionObject?,
    val targetClassDeclaration: KSClassDeclaration,
    val mixinAnnotations: List<MixinAnnotation>,
    val typeParameters: List<TypeParameter>,
) {
    sealed interface ClassKind
    class Class(
        val isAbstract: Boolean,
        val constructorParameters: List<ConstructorParameter>,
    ) : ClassKind {
        sealed interface ConstructorParameter {
            class Origin(val name: String, val type: Type) : ConstructorParameter
        }
    }

    data object Interface : ClassKind

    sealed interface Extension {

        val receiverType: Type

        class Property(
            override val name: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: Type,
            override val receiverType: Type,
        ) : DuckSource.Property,
            Extension

        class Function(
            override val name: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameter>,
            override val returnType: Type?,
            override val receiverType: Type,
            override val typeParameters: List<TypeParameter>,
        ) : DuckSource.Function,
            Extension
    }

    sealed interface Shadow {

        val modifiers: EnumSet<Modifier>
        val mappingName: String
        val mixinAnnotations: List<MixinAnnotation>

        class Property(
            override val name: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: Type,
            override val modifiers: EnumSet<Modifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotation>,
        ) : DuckSource.Property,
            Shadow

        class Function(
            override val name: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameter>,
            override val returnType: Type?,
            override val modifiers: EnumSet<Modifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotation>,
            override val typeParameters: List<TypeParameter>,
        ) : DuckSource.Function,
            Shadow
    }

    class Injection(
        val jvmName: String,
        val extensionReceiverType: Type?,
        val mixinAnnotations: List<MixinAnnotation>,
        val parameters: List<Parameter>,
        val returnType: Type?,
        val typeParameters: List<TypeParameter>,
    ) {
        class Parameter(
            val name: String,
            val type: Type,
            val mixinAnnotations: List<MixinAnnotation>,
        )
    }

    class CompanionObject(val name: String, val injections: List<Injection>)
}

class Type(
    val ksType: KSType,
    val arguments: List<Argument>,
    val canonicalType: Type?,
    val isAny: Boolean,
    val isUnit: Boolean,
    val isInterface: Boolean,
    val classDeclaration: KSClassDeclaration?,
) {
    sealed interface Argument
    object StarArgument : Argument

    sealed interface TypedArgument : Argument {
        val type: Type
    }

    class InvariantArgument(override val type: Type) : TypedArgument
    class CovariantArgument(override val type: Type) : TypedArgument
    class ContravariantArgument(override val type: Type) : TypedArgument
}

class TypeParameter(
    val name: String,
    val bounds: List<Type>,
)
