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

        val typeParameters: List<TypeParameterModel>
        val contextParameters: List<ContextParameterModel>

        class Property(
            override val declaredName: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: TypeModel,
            override val typeParameters: List<TypeParameterModel>,
            override val contextParameters: List<ContextParameterModel>,
        ) : DuckSourceModel.Property,
            Extension

        class Function(
            override val declaredName: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameterModel>,
            override val returnType: TypeModel?,
            override val typeParameters: List<TypeParameterModel>,
            override val contextParameters: List<ContextParameterModel>,
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
        val contextParameters: List<Parameter>,
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

class ContextParameterModel(
    val name: String,
    val type: TypeModel,
)

sealed interface TypeModel {
    val isNullable: Boolean
}

class ClassTypeModel(
    val packageName: String,
    val qualifiedName: String,
    val arguments: List<TypeArgument> = emptyList(),
    val canonicalType: ClassTypeModel? = null,
    val functionalTypeDetails: FunctionalTypeDetails? = null,
    override val isNullable: Boolean = false,
) : TypeModel {

    val canonicalOrSelf: ClassTypeModel get() = canonicalType ?: this

    sealed interface TypeArgument
    object StarProjectionArgument : TypeArgument
    class GenericTypeArgument(val type: TypeModel, val variance: VarianceType = VarianceType.INVARIANT) : TypeArgument

    class FunctionalTypeDetails(
        val receiverType: TypeModel?,
        val parameters: List<Parameter>,
        val returnType: TypeModel,
    ) {
        class Parameter(val name: String?, val type: TypeModel)
    }

    companion object {
        val NULLABLE_ANY = ClassTypeModel(packageName = "kotlin", qualifiedName = "kotlin.Any", isNullable = true)
    }
}

class TypeArgumentModel(
    val name: String,
    val canonicalType: ClassTypeModel = ClassTypeModel.NULLABLE_ANY,
    override val isNullable: Boolean = false,
) : TypeModel

class TypeParameterModel(
    val name: String,
    val bounds: List<TypeModel>,
)
