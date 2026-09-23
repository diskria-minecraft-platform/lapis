package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.annotations.Side
import java.util.*
import javax.lang.model.element.Modifier

class Patch(
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
    val targetType: Type,
    val mixinAnnotations: List<MixinAnnotation>,
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
        ) : DuckSource.Function,
            Shadow
    }

    class Injection(
        val jvmName: String,
        val extensionReceiverType: Type?,
        val mixinAnnotations: List<MixinAnnotation>,
        val parameters: List<Parameter>,
        val returnType: Type?,
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
    val type: KSType,
    val isAny: Boolean,
    val isUnit: Boolean,
    val isInterface: Boolean,
) {
    fun isSubtypeOf(other: Type): Boolean = type.isAssignableFrom(other.type)
}
