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
    val classDeclaration: ClassDeclarationModel,
    val name: String,
    val side: Side,
    val initStrategy: InitStrategy,
    val classKind: ClassKind,
    val shadowSources: List<Shadow>,
    val extensionSources: List<Extension>,
    val injections: List<Injection>,
    val companionObject: CompanionObject?,
    val targetClassDeclaration: ClassDeclarationModel,
    val mixinAnnotations: List<MixinAnnotationModel>,
    val typeParameters: List<TypeParameterModel>,
) {
    sealed interface ClassKind
    class Class(
        val isAbstract: Boolean,
        val constructorParameters: List<ConstructorParameter>,
    ) : ClassKind {
        sealed interface ConstructorParameter {
            class Origin(val name: String, val type: TypeModel) : ConstructorParameter
        }
    }

    data object Interface : ClassKind

    sealed interface Extension {

        val receiverType: TypeModel

        class Property(
            override val declaredName: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: TypeModel,
            override val receiverType: TypeModel,
        ) : DuckSourceModel.Property,
            Extension

        class Function(
            override val declaredName: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameterModel>,
            override val returnType: TypeModel?,
            override val receiverType: TypeModel,
            override val typeParameters: List<TypeParameterModel>,
        ) : DuckSourceModel.Function,
            Extension
    }

    sealed interface Shadow {

        val modifiers: EnumSet<Modifier>
        val mappingName: String
        val mixinAnnotations: List<MixinAnnotationModel>

        class Property(
            override val declaredName: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: TypeModel,
            override val modifiers: EnumSet<Modifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotationModel>,
        ) : DuckSourceModel.Property,
            Shadow

        class Function(
            override val declaredName: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameterModel>,
            override val returnType: TypeModel?,
            override val modifiers: EnumSet<Modifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotationModel>,
            override val typeParameters: List<TypeParameterModel>,
        ) : DuckSourceModel.Function,
            Shadow
    }

    class Injection(
        val jvmName: String,
        val extensionReceiverType: TypeModel?,
        val mixinAnnotations: List<MixinAnnotationModel>,
        val parameters: List<Parameter>,
        val returnType: TypeModel?,
        val typeParameters: List<TypeParameterModel>,
    ) {
        class Parameter(
            val name: String,
            val type: TypeModel,
            val mixinAnnotations: List<MixinAnnotationModel>,
        )
    }

    class CompanionObject(val name: String, val injections: List<Injection>)
}

class TypeModel(
    val ksType: KSType,
    val arguments: List<Argument>,
    val canonicalType: TypeModel?,
    val classDeclaration: KSClassDeclaration?,
) {
    sealed interface Argument
    object StarArgument : Argument

    sealed interface TypedArgument : Argument {
        val type: TypeModel
    }

    class InvariantArgument(override val type: TypeModel) : TypedArgument
    class CovariantArgument(override val type: TypeModel) : TypedArgument
    class ContravariantArgument(override val type: TypeModel) : TypedArgument
}

class TypeParameterModel(
    val name: String,
    val bounds: List<TypeModel>,
)

class ClassDeclarationModel(
    val packageName: String,
    val qualifiedName: String,
)
