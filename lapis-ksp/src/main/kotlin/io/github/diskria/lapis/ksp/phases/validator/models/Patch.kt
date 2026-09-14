package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.java.JPModifier

class Patch(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,
    val name: String,
    val env: Env,
    val initStrategy: InitStrategy,
    val isImplRequired: Boolean,
    val constructorParameters: List<ConstructorParameter>,
    val duckSources: List<DuckSource>,
    val injections: List<Injection>,
    val targetClassDeclaration: KSClassDeclaration?,
    val mixinAnnotations: List<MixinAnnotation>,
) {
    val containingFile: KSFile? = symbol.containingFile
    val className: XClassName = classDeclaration.toXClassName()

    sealed interface ConstructorParameter {
        class Origin(val classDeclaration: KSClassDeclaration) : ConstructorParameter
    }

    sealed interface Extension {

        val receiverClassDeclaration: KSClassDeclaration

        class Property(
            override val name: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: KSType,
            override val receiverClassDeclaration: KSClassDeclaration,
        ) : DuckSource.Property,
            Extension

        class Function(
            override val name: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameter>,
            override val returnType: KSType?,
            override val receiverClassDeclaration: KSClassDeclaration,
        ) : DuckSource.Function,
            Extension
    }

    sealed interface Shadow {

        val modifiers: Set<JPModifier>
        val mappingName: String
        val mixinAnnotations: List<MixinAnnotation>

        class Property(
            override val name: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: KSType,
            override val modifiers: Set<JPModifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotation>,
        ) : DuckSource.Property,
            Shadow

        class Function(
            override val name: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameter>,
            override val returnType: KSType?,
            override val modifiers: Set<JPModifier>,
            override val mappingName: String,
            override val mixinAnnotations: List<MixinAnnotation>,
        ) : DuckSource.Function,
            Shadow
    }

    class Injection(
        val jvmName: String,
        val extensionReceiverClassDeclaration: KSClassDeclaration?,
        val mixinAnnotations: List<MixinAnnotation>,
        val isStatic: Boolean,
        val parameters: List<Parameter>,
        val returnType: KSType?,
    ) {
        val extensionReceiverClassName: XClassName? get() = extensionReceiverClassDeclaration?.toXClassName()

        class Parameter(
            val name: String,
            val type: KSType,
            val mixinAnnotations: List<MixinAnnotation>,
        )
    }
}
