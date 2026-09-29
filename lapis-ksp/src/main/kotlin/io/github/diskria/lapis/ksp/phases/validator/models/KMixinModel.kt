package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.ksp.utils.VarianceType
import java.util.*
import javax.lang.model.element.Modifier

class KMixinModel(
    val containingFile: KSFile?,
    val type: ClassTypeModel,
    val name: String,
    val side: Side,
    val initStrategy: InitStrategy,
    val classKind: ClassKind,
    val shadowSources: List<Shadow>,
    val extensionSources: List<Extension>,
    val injections: List<Injection>,
    val companionObject: CompanionObject?,
    val targetType: ClassTypeModel,
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

        class Property(
            override val declaredName: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: TypeModel,
        ) : DuckSourceModel.Property,
            Extension

        class Function(
            override val declaredName: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameterModel>,
            override val returnType: TypeModel?,
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
    ) {
        class Parameter(
            val name: String,
            val type: TypeModel,
            val mixinAnnotations: List<MixinAnnotationModel>,
        )
    }

    class CompanionObject(val name: String, val injections: List<Injection>)
}

sealed interface TypeModel {
    val isNullable: Boolean
}

class ClassTypeModel(
    val packageName: String,
    val qualifiedName: String,
    val arguments: List<TypeArgument>,
    val canonicalType: ClassTypeModel?,
    override val isNullable: Boolean,
) : TypeModel {
    sealed interface TypeArgument
    object StarProjectionArgument : TypeArgument
    class GenericTypeArgument(val type: TypeModel, val variance: VarianceType) : TypeArgument
}

class TypeArgumentModel(
    val name: String,
    val canonicalType: ClassTypeModel,
    override val isNullable: Boolean,
) : TypeModel

class TypeParameterModel(
    val name: String,
    val variance: VarianceType,
    val bounds: List<TypeModel>,
)
