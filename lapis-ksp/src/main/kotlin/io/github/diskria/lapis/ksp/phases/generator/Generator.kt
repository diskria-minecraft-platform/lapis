package io.github.diskria.lapis.ksp.phases.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.Logger
import io.github.diskria.lapis.ksp.kspPoetesse
import io.github.diskria.lapis.ksp.phases.generator.models.GeneratedMixinsJson
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import org.spongepowered.asm.mixin.*

class Generator(
    private val kspOptions: KspOptions,
    private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: Logger,
) {
    fun generate(patches: List<IrPatch>) {
        patches.forEach { patch ->
            patch.impl?.let { generatePatchImpl(it, patch) }
            patch.mixin.duck?.let { generateMixinDuck(it) }
            generateMixin(patch.mixin, patch.className, patch.impl)
        }
        generateMixinConfig(patches.map { it.mixin })
        // todo pass extensions to fir via gen res
    }

    private fun generatePatchImpl(patchImpl: IrPatchImpl, patch: IrPatch) {
        kspPoetesse {
            kotlin.file(patchImpl.className) {
                class_(fileName) {
                    public()
                    if (patchImpl.constructorParameters.isNotEmpty()) {
                        constructor(primary = true) {
                            public()
                            patchImpl.constructorParameters.forEach { parameter ->
                                when (parameter) {
                                    is IrPatchImpl.ConstructorParameter.Instance -> {
                                        parameter("instance", parameter.className)
                                            .property { private() }
                                    }

                                    is IrPatchImpl.ConstructorParameter.Duck -> {
                                        parameter("duck", requireNotNull(patch.mixin.duck?.className))
                                            .property { private() }
                                    }
                                }
                            }
                        }
                    }
                    superclass(patch.className) {
                        patch.constructorArguments.forEach { argument ->
                            when (argument) {
                                is IrPatch.ConstructorArgument.Origin -> {
                                    argument { N("instance") }
                                }
                            }
                        }
                    }
                    patch.mixin.duck?.entries?.filterIsInstance<IrMixinDuck.Shadow>()?.forEach { entry ->
                        when (entry) {
                            is IrMixinDuck.Property -> {
                                property(entry.sourceName, entry.typeName) {
                                    override()
                                    getter {
                                        expression {
                                            "${N("duck")}.${(N(entry.getter.name))}()"
                                        }
                                    }
                                    entry.setter?.let { setter ->
                                        setter { parameterName ->
                                            body {
                                                line { "${N("duck")}.${N(setter.name)}(${N(parameterName)})" }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrMixinDuck.Function -> {
                                function(entry.sourceName) {
                                    override()
                                    entry.parameters.forEach { parameter(it.name, it.typeName) }
                                    entry.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (entry.returnTypeName != null) "return " else ""
                                        val parameters = code {
                                            entry.parameters.joinToString { N(it.name) }
                                        }
                                        line { "$maybeReturn${N("duck")}.${N(entry.name)}(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(codeGenerator, aggregating = false, patchImpl.originatingFiles)
    }

    private fun generateMixin(mixin: IrMixin, patchClassName: XClassName, patchImpl: IrPatchImpl?) {
        kspPoetesse {
            java.file(mixin.className) {
                class_(fileName) {
                    public()
                    abstract()
                    if (mixin.annotations.isNotEmpty()) {
                        mixinAnnotations(mixin.annotations)
                    } else {
                        annotation<Mixin> {
                            member(Mixin::value, requireNotNull(mixin.targetClassName))
                        }
                    }
                    val patchImplMember = patchImpl?.let { patchImplMember(it) }
                    mixin.duck?.let { duck ->
                        superinterface(duck.className)
                        duck.entries.forEach { entry ->
                            when (entry) {
                                is IrMixinDuck.Extension -> {
                                    entry.kinds.forEach { kind ->
                                        method(kind.name) {
                                            public()
                                            annotation<Override>()
                                            kind.parameters.forEach { parameter(it.name, it.typeName) }
                                            kind.returnTypeName?.let { returns(it) }
                                            body {
                                                val maybeReturn = if (kind.returnTypeName != null) "return " else ""
                                                val parameters = code {
                                                    kind.parameters.joinToString { it.name }
                                                }
                                                line {
                                                    "$maybeReturn$patchImplMember.${L(kind.sourceJvmName)}" +
                                                        "(${L(parameters)})"
                                                }
                                            }
                                        }
                                    }
                                }

                                is IrMixinDuck.Shadow -> when (entry) {
                                    is IrMixinDuck.Shadow.Property -> {
                                        val shadowField = field(entry.mappingName, entry.typeName) {
                                            if (entry.mixinAnnotations.isNotEmpty()) {
                                                mixinAnnotations(entry.mixinAnnotations)
                                            } else {
                                                if (entry.setter != null) annotation<Mutable>()
                                                if (entry.isFinal) annotation<Final>()
                                                annotation<Shadow>()
                                            }
                                            entry.modifiers.forEach { modifier(it) }
                                        }
                                        entry.kinds.forEach { kind ->
                                            method(kind.name) {
                                                public()
                                                annotation<Override>()
                                                kind.parameters.forEach { parameter(it.name, it.typeName) }
                                                kind.returnTypeName?.let { returns(it) }
                                                body {
                                                    when (kind) {
                                                        is IrMixinDuck.Property.Getter -> {
                                                            line { "return $shadowField" }
                                                        }

                                                        is IrMixinDuck.Property.Setter -> {
                                                            line { "$shadowField = ${kind.name}" }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is IrMixinDuck.Shadow.Function -> {
                                        val shadowMethod = method(entry.mappingName) {
                                            val isStatic = JPModifier.STATIC in entry.modifiers
                                            if (isStatic) public()
                                            if (entry.mixinAnnotations.isNotEmpty()) {
                                                mixinAnnotations(entry.mixinAnnotations)
                                            } else {
                                                annotation<Shadow>()
                                            }
                                            entry.modifiers.forEach { modifier(it) }
                                            entry.parameters.forEach { parameter(it.name, it.typeName) }
                                            entry.returnTypeName?.let { returns(it) }
                                            if (isStatic) {
                                                body {
                                                    line { "throw ${T<AssertionError>()}(${S("Stub!")})" }
                                                }
                                            }
                                        }
                                        method(entry.name) {
                                            public()
                                            annotation<Override>()
                                            entry.parameters.forEach { parameter(it.name, it.typeName) }
                                            entry.returnTypeName?.let { returns(it) }
                                            body {
                                                val maybeReturn = if (entry.returnTypeName != null) "return " else ""
                                                val parameters = code {
                                                    entry.parameters.joinToString { it.name }
                                                }
                                                line { "$maybeReturn$shadowMethod(${L(parameters)})" }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    mixin.injections.forEach { injection ->
                        mixinInjection(injection) {
                            if (injection.isStatic) "${T(patchClassName)}.Companion"
                            else requireNotNull(patchImplMember)
                        }
                    }
                }
            }
        }.writeWith(codeGenerator, aggregating = false, mixin.originatingFiles)
    }

    private fun JavaAnnotationTrait.mixinAnnotations(mixinAnnotations: List<IrMixinAnnotation>) {
        mixinAnnotations.forEach { +mixinAnnotation(it) }
    }

    private fun mixinAnnotation(annotation: IrMixinAnnotation): JavaTypedAnnotationRef<Annotation> =
        kspPoetesse.java.annotation(annotation.className) {
            annotation.arguments.forEach { argument ->
                member(argument.name) {
                    val scalarValue = argument.takeIf { !it.isArray }?.values?.singleOrNull()
                    if (scalarValue != null) {
                        mixinAnnotationArgumentValue(scalarValue)
                    } else {
                        argument.values.joinToString(prefix = "{", postfix = "}") { mixinAnnotationArgumentValue(it) }
                    }
                }
            }
        }

    private fun JavaCodeScope.mixinAnnotationArgumentValue(value: IrMixinAnnotation.Argument.Value): String =
        when (value) {
            is IrMixinAnnotation.Argument.BooleanValue -> L(value.boolean)
            is IrMixinAnnotation.Argument.ByteValue -> L(value.byte)
            is IrMixinAnnotation.Argument.ShortValue -> L(value.short)
            is IrMixinAnnotation.Argument.IntValue -> L(value.int)
            is IrMixinAnnotation.Argument.LongValue -> L(value.long)
            is IrMixinAnnotation.Argument.CharValue -> L(value.char)
            is IrMixinAnnotation.Argument.FloatValue -> L(value.float)
            is IrMixinAnnotation.Argument.DoubleValue -> L(value.double)
            is IrMixinAnnotation.Argument.StringValue -> S(value.string)
            is IrMixinAnnotation.Argument.EnumValue -> "${T(value.className)}.${L(value.entryName)}"
            is IrMixinAnnotation.Argument.ClassValue -> "${T(value.typeName)}.class"
            is IrMixinAnnotation.Argument.AnnotationValue -> L(mixinAnnotation(value.annotation))
        }

    private fun JavaTypeScope.patchImplMember(patchImpl: IrPatchImpl): String {
        val isEager = patchImpl.initStrategy == InitStrategy.Eager
        val isSynchronized = patchImpl.initStrategy == InitStrategy.Synchronized
        val isThreadSafe = patchImpl.initStrategy == InitStrategy.Volatile || isSynchronized
        val patchField = field("patch", patchImpl.className) {
            private()
            annotation<Unique>()
            if (isEager) {
                final()
                initializer { patchImplInitializer(patchImpl) }
            } else if (isThreadSafe) {
                volatile()
            }
        }
        if (isEager) return patchField
        val patchLockField = if (isSynchronized) {
            field<Any>("patchLock") {
                private()
                final()
                annotation<Unique>()
                initializer { "new ${T<Any>()}()" }
            }
        } else null
        val getOrInitPatchMethod = method("getOrInitPatch") {
            private()
            annotation<Unique>()
            body {
                if (isThreadSafe) {
                    val local by var_(patchImpl.className) { "this.$patchField" }
                    controlFlow {
                        branch("if ($local == null)") {
                            if (isSynchronized && patchLockField != null) {
                                controlFlow {
                                    branch("synchronized (this.$patchLockField)") {
                                        line { "$local = this.$patchField" }
                                        controlFlow {
                                            branch("if ($local == null)") {
                                                line { "$local = ${L { patchImplInitializer(patchImpl) }}" }
                                                line { "this.$patchField = $local" }
                                            }
                                        }
                                    }
                                }
                            } else {
                                line { "$local = ${L { patchImplInitializer(patchImpl) }}" }
                                line { "this.$patchField = $local" }
                            }
                        }
                    }
                    line { "return $local" }
                } else {
                    controlFlow {
                        branch("if (this.$patchField == null)") {
                            line { "this.$patchField = ${L { patchImplInitializer(patchImpl) }}" }
                        }
                    }
                    line { "return this.$patchField" }
                }
            }
            returns(patchImpl.className)
        }
        return "$getOrInitPatchMethod()"
    }

    fun JavaCodeScope.patchImplInitializer(patchImpl: IrPatchImpl): String {
        val constructorArguments = code {
            patchImpl.constructorParameters.joinToString { parameter ->
                when (parameter) {
                    is IrPatchImpl.ConstructorParameter.Instance -> doubleCastTo(parameter.className)
                    is IrPatchImpl.ConstructorParameter.Duck -> "this"
                }
            }
        }
        return "new ${T(patchImpl.className)}(${L(constructorArguments)})"
    }

    fun JavaCodeScope.doubleCastTo(targetTypeName: XTypeName): String = "(${T(targetTypeName)}) (${T<Any>()}) this"

    private fun JavaTypeScope.mixinInjection(injection: IrMixin.Injection, patchReceiver: JavaCodeScope.() -> String) {
        method(injection.jvmName) {
            private()
            if (injection.isStatic) static()
            mixinAnnotations(injection.mixinAnnotations)
            injection.parameters.forEach { parameter ->
                parameter(parameter.name, parameter.typeName) {
                    mixinAnnotations(parameter.mixinAnnotations)
                }
            }
            injection.returnTypeName?.let { returns(it) }
            body {
                val functionArguments = code {
                    buildList {
                        injection.extensionReceiverClassName?.let { add(doubleCastTo(it)) }
                        addAll(injection.parameters.map { it.name })
                    }.joinToString()
                }
                val maybeReturn = if (injection.returnTypeName != null) "return " else ""
                line { "$maybeReturn${L(patchReceiver)}.${L(injection.jvmName)}(${L(functionArguments)})" }
            }
        }
    }

    private fun generateMixinDuck(duck: IrMixinDuck) {
        kspPoetesse {
            java.file(duck.className) {
                interface_(fileName) {
                    public()
                    abstract()
                    duck.entries.flatMap { it.kinds }.forEach { kind ->
                        method(kind.name) {
                            public()
                            abstract()
                            kind.parameters.forEach { parameter(it.name, it.typeName) }
                            kind.returnTypeName?.let { returns(it) }
                        }
                    }
                }
            }
        }.writeWith(codeGenerator, aggregating = false, duck.originatingFiles)
    }

    private fun generateMixinConfig(mixinBlueprints: List<IrMixin>) {
        generateResourceFile(
            "generated-mixins.json",
            mixinBlueprints.flatMap { it.originatingFiles },
            aggregating = true,
        ) {
            val envClassNames = mixinBlueprints.groupBy({ it.env }, { it.className })
            Json.encodeToString(GeneratedMixinsJson.of(kspOptions.mixinPackage, envClassNames))
        }
    }

    private fun generateResourceFile(
        fileName: String,
        originatingKSFiles: Iterable<KSFile>,
        aggregating: Boolean,
        buildText: () -> String,
    ) {
        codeGenerator.createNewFileByPath(
            dependencies = Dependencies(aggregating, *originatingKSFiles.toList().toTypedArray()),
            path = "lapis-intermediates/$fileName",
            extensionName = "",
        ).writer().use { it.write(buildText()) }
    }
}

fun PoetesseFile.writeWith(codeGenerator: CodeGenerator, aggregating: Boolean, originatingKSFiles: Iterable<KSFile>) {
    codeGenerator.createNewFile(
        dependencies = Dependencies(aggregating, *originatingKSFiles.toList().toTypedArray()),
        packageName = packageName.orEmpty(),
        fileName = fileName,
        extensionName = extensionName,
    ).writer().use(::writeTo)
}
