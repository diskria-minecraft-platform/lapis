package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.kspPoetesse
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.interop.xClass
import io.github.diskria.poetesse.interop.xType
import io.github.diskria.poetesse.java.JPModifier

class Patch(
    private val symbol: KSNode,
    private val classDeclaration: KSClassDeclaration,
    val name: String,
    val env: Env,
    val initStrategy: InitStrategy,
    val classKind: TargetKind,
    val duckSources: List<DuckSource>,
    val injections: List<Injection>,
    val companionObject: CompanionObject?,
    private val targetType: KSType,
    val mixinAnnotations: List<MixinAnnotation>,
) {
    val containingFile: KSFile? get() = symbol.containingFile
    val className: XClassName get() = classDeclaration.toXClassName()
    val targetTypeName: XTypeName get() = targetType.toXTypeName()

    sealed interface TargetKind
    class Class(
        val isAbstract: Boolean,
        val constructorParameters: List<ConstructorParameter>,
    ) : TargetKind {
        sealed interface ConstructorParameter {
            class Origin(val name: String, val type: TargetType) : ConstructorParameter
        }
    }

    data object Interface : TargetKind

    sealed interface Extension {

        val receiverType: TargetType

        class Property(
            override val name: String,
            override val getterJvmName: String,
            override val setterJvmName: String?,
            override val type: KSType,
            override val receiverType: TargetType,
        ) : DuckSource.Property,
            Extension

        class Function(
            override val name: String,
            override val jvmName: String,
            override val parameters: List<FunctionParameter>,
            override val returnType: KSType?,
            override val receiverType: TargetType,
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
        val extensionReceiverType: TargetType?,
        val mixinAnnotations: List<MixinAnnotation>,
        val isStatic: Boolean,
        val parameters: List<Parameter>,
        private val returnType: KSType?,
    ) {
        val returnTypeName: XTypeName? get() = returnType?.toXTypeName()

        class Parameter(
            val name: String,
            private val type: KSType,
            val mixinAnnotations: List<MixinAnnotation>,
        ) {
            val typeName: XTypeName get() = type.toXTypeName()
        }
    }

    class CompanionObject(val name: String, val injections: List<Injection>)
}

class TargetType(
    private val type: KSType,
    val isInterface: Boolean,
    val isAny: Boolean,
) {
    val typeName: XTypeName get() = type.toXTypeName()
}

fun KSType.toXTypeName(): XTypeName =
    kspPoetesse.xType(toTypeName())

fun KSClassDeclaration.toXClassName(): XClassName =
    kspPoetesse.xClass(toClassName())
