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
import io.github.diskria.lapis.ksp.phases.lowering.withSuffix
import io.github.diskria.poetesse.PoetesseFile
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
            patch.mixin.duck?.let {
                generateMixinDuck(it)
                generateExtensions(it, patch)
            }
            when (patch) {
                is IrPatchClass -> {
                    patch.impl?.let { generatePatchImpl(it, patch) }
                    generateMixinClass(patch.mixin, patch)
                }

                is IrPatchInterface -> generateMixinInterface()
            }
        }
        generateMixinConfig(patches.map { it.mixin })
    }

    private fun generateMixinDuck(duck: IrMixinDuck) {
        kspPoetesse {
            java.file(duck.className) {
                interface_(fileName) {
                    public()
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
        }.writeWith(aggregating = false, listOfNotNull(duck.patchOriginatingFile))
    }

    // todo migrate to FIR plugin
    private fun generateExtensions(duck: IrMixinDuck, patch: IrPatch) {
        val entries = duck.entries.filterIsInstance<IrMixinDuck.Extension>().ifEmpty { return }
        kspPoetesse {
            kotlin.file(patch.className.withSuffix("_Extensions")) {
                entries.forEach { entry ->
                    when (entry) {
                        is IrMixinDuck.Extension.Property -> {
                            property(entry.sourceName, entry.typeName) {
                                public()
                                inline()
                                extensionReceiver(entry.receiverType.typeName)
                                getter {
                                    expression {
                                        "(this as ${T(duck.className)}).${(N(entry.getter.name))}()"
                                    }
                                }
                                entry.setter?.let { setter ->
                                    setter { newValue ->
                                        body {
                                            line { "(this as ${T(duck.className)}).${N(setter.name)}(${N(newValue)})" }
                                        }
                                    }
                                }
                            }
                        }

                        is IrMixinDuck.Extension.Function -> {
                            function(entry.sourceName) {
                                public()
                                inline()
                                extensionReceiver(entry.receiverType.typeName)
                                entry.parameters.forEach { parameter(it.name, it.typeName) }
                                entry.returnTypeName?.let { returns(it) }
                                body {
                                    val maybeReturn = if (entry.returnTypeName != null) "return " else ""
                                    val parameters = code { entry.parameters.joinToString { N(it.name) } }
                                    line {
                                        "$maybeReturn(this as ${T(duck.className)}).${N(entry.name)}(${L(parameters)})"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(duck.patchOriginatingFile))
    }

    private fun generatePatchImpl(patchImpl: IrPatchImpl, patch: IrPatchClass) {
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
                                        parameter(parameter.name, parameter.type.typeName)
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
                        patch.constructorParameters.forEach { parameter ->
                            argument {
                                when (parameter) {
                                    is IrPatchClass.ConstructorParameter.Origin -> N(parameter.name)
                                }
                            }
                        }
                    }
                    patch.mixin.duck?.entries?.filterIsInstance<IrMixinDuck.Shadow>()?.forEach { entry ->
                        when (entry) {
                            is IrMixinDuck.Property -> {
                                property(entry.sourceName, entry.typeName) {
                                    public()
                                    override()
                                    getter {
                                        expression {
                                            "${N("duck")}.${(N(entry.getter.name))}()"
                                        }
                                    }
                                    entry.setter?.let { setter ->
                                        setter { newValue ->
                                            body {
                                                line { "${N("duck")}.${N(setter.name)}(${N(newValue)})" }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrMixinDuck.Function -> {
                                function(entry.sourceName) {
                                    public()
                                    override()
                                    entry.parameters.forEach { parameter(it.name, it.typeName) }
                                    entry.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (entry.returnTypeName != null) "return " else ""
                                        val parameters = code { entry.parameters.joinToString { N(it.name) } }
                                        line { "$maybeReturn${N("duck")}.${N(entry.name)}(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(patchImpl.patchOriginatingFile))
    }

    private fun generateMixinClass(mixin: IrMixin, patch: IrPatchClass) {
        kspPoetesse {
            java.file(mixin.className) {
                class_(fileName) {
                    public()
                    abstract()
                    if (mixin.annotations.isNotEmpty()) {
                        mixinAnnotations(mixin.annotations)
                    } else {
                        annotation<Mixin> {
                            member(Mixin::value, mixin.targetTypeName)
                        }
                    }
                    mixin.duck?.let { superinterface(it.className) }
                    val duckExtensionEntries = mixin.duck?.entries?.filterIsInstance<IrMixinDuck.Extension>().orEmpty()
                    val memberInjections = mixin.injections.filterIsInstance<IrMixin.MemberInjection>()
                    val patchMember = if (duckExtensionEntries.isNotEmpty() || memberInjections.isNotEmpty()) {
                        patchMember(patch)
                    } else null
                    mixin.duck?.entries?.filterIsInstance<IrMixinDuck.Shadow>()?.forEach { shadowEntry ->
                        when (shadowEntry) {
                            is IrMixinDuck.Shadow.Property -> {
                                val shadowField = field(shadowEntry.mappingName, shadowEntry.typeName) {
                                    if (shadowEntry.mixinAnnotations.isNotEmpty()) {
                                        mixinAnnotations(shadowEntry.mixinAnnotations)
                                    } else {
                                        if (shadowEntry.setter != null) annotation<Mutable>()
                                        if (shadowEntry.isFinal) annotation<Final>()
                                        annotation<Shadow>()
                                    }
                                    shadowEntry.modifiers.forEach { modifier(it) }
                                }
                                shadowEntry.kinds.forEach { kind ->
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
                                                    line { "$shadowField = ${kind.parameter.name}" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrMixinDuck.Shadow.Function -> {
                                val shadowMethod = method(shadowEntry.mappingName) {
                                    val isStatic = JPModifier.STATIC in shadowEntry.modifiers
                                    if (isStatic) public()
                                    if (shadowEntry.mixinAnnotations.isNotEmpty()) {
                                        mixinAnnotations(shadowEntry.mixinAnnotations)
                                    } else {
                                        annotation<Shadow>()
                                    }
                                    shadowEntry.modifiers.forEach { modifier(it) }
                                    shadowEntry.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadowEntry.returnTypeName?.let { returns(it) }
                                    if (isStatic) {
                                        body {
                                            line { "throw ${T<AssertionError>()}(${S("Stub!")})" }
                                        }
                                    }
                                }
                                method(shadowEntry.name) {
                                    public()
                                    annotation<Override>()
                                    shadowEntry.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadowEntry.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (shadowEntry.returnTypeName != null) "return " else ""
                                        val parameters = code { shadowEntry.parameters.joinToString { it.name } }
                                        line { "$maybeReturn$shadowMethod(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                    if (patchMember != null) {
                        duckExtensionEntries.flatMap { it.kinds }.forEach { kind ->
                            method(kind.name) {
                                public()
                                annotation<Override>()
                                kind.parameters.forEach { parameter(it.name, it.typeName) }
                                kind.returnTypeName?.let { returns(it) }
                                body {
                                    val maybeReturn = if (kind.returnTypeName != null) "return " else ""
                                    val parameters = code { kind.parameters.joinToString { it.name } }
                                    line { "$maybeReturn$patchMember.${L(kind.sourceJvmName)}(${L(parameters)})" }
                                }
                            }
                        }
                        memberInjections.forEach { mixinInjection(it) { patchMember } }
                    }
                    mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                        mixinInjection(staticInjection) {
                            "${T(patch.className)}.${L(staticInjection.patchCompanionName)}"
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(mixin.patchOriginatingFile))
    }

    private fun JavaCodeScope.patchInitializer(patch: IrPatchClass): String {
        val (className, arguments) = if (patch.impl != null) {
            patch.impl.className to code {
                patch.impl.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is IrPatchImpl.ConstructorParameter.Instance -> targetTypeCast(parameter.type)
                        is IrPatchImpl.ConstructorParameter.Duck -> "this"
                    }
                }
            }
        } else {
            patch.className to code {
                patch.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is IrPatchClass.ConstructorParameter.Origin -> targetTypeCast(parameter.type)
                    }
                }
            }
        }
        return "new ${T(className)}(${L(arguments)})"
    }

    private fun JavaTypeScope.patchMember(patch: IrPatchClass): String {
        val isEager = patch.initStrategy == InitStrategy.Eager
        val isSynchronized = patch.initStrategy == InitStrategy.Synchronized
        val isThreadSafe = patch.initStrategy == InitStrategy.Volatile || isSynchronized
        val patchField = field("patch", patch.className) {
            private()
            annotation<Unique>()
            if (isEager) {
                final()
                initializer { patchInitializer(patch) }
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
                    val local by var_(patch.className) { "this.$patchField" }
                    controlFlow {
                        branch("if ($local == null)") {
                            if (isSynchronized && patchLockField != null) {
                                controlFlow {
                                    branch("synchronized (this.$patchLockField)") {
                                        line { "$local = this.$patchField" }
                                        controlFlow {
                                            branch("if ($local == null)") {
                                                line { "$local = ${L { patchInitializer(patch) }}" }
                                                line { "this.$patchField = $local" }
                                            }
                                        }
                                    }
                                }
                            } else {
                                line { "$local = ${L { patchInitializer(patch) }}" }
                                line { "this.$patchField = $local" }
                            }
                        }
                    }
                    line { "return $local" }
                } else {
                    controlFlow {
                        branch("if (this.$patchField == null)") {
                            line { "this.$patchField = ${L { patchInitializer(patch) }}" }
                        }
                    }
                    line { "return this.$patchField" }
                }
            }
            returns(patch.className)
        }
        return "$getOrInitPatchMethod()"
    }

    private fun generateMixinInterface() {

    }

    private fun JavaTypeScope.mixinInjection(injection: IrMixin.Injection, patchReceiver: JavaCodeScope.() -> String) {
        method(injection.name) {
            private()
            if (injection is IrMixin.StaticInjection) static()
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
                        if (injection is IrMixin.MemberInjection) {
                            injection.extensionReceiverType?.let { add(targetTypeCast(it)) }
                        }
                        addAll(injection.parameters.map { it.name })
                    }.joinToString()
                }
                val maybeReturn = if (injection.returnTypeName != null) "return " else ""
                line { "$maybeReturn${L(patchReceiver)}.${L(injection.sourceJvmName)}(${L(functionArguments)})" }
            }
        }
    }

    private fun JavaAnnotationTrait.mixinAnnotations(mixinAnnotations: List<IrMixinAnnotation>) {
        mixinAnnotations.forEach { +mixinAnnotation(it) }
    }

    private fun mixinAnnotation(annotation: IrMixinAnnotation): JavaTypedAnnotationRef<Annotation> =
        kspPoetesse.java.annotation(annotation.typeClassName) {
            annotation.arguments.forEach { argument ->
                member(argument.name) {
                    when (argument) {
                        is IrMixinAnnotation.ScalarArgument -> mixinAnnotationArgumentValue(argument.value)
                        is IrMixinAnnotation.ArrayArgument -> {
                            argument.elements.joinToString(prefix = "{", postfix = "}") {
                                mixinAnnotationArgumentValue(it)
                            }
                        }
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
            is IrMixinAnnotation.Argument.TypeValue -> "${T(value.typeName)}.class"
            is IrMixinAnnotation.Argument.AnnotationValue -> L(mixinAnnotation(value.annotation))
        }

    private fun JavaCodeScope.targetTypeCast(targetType: IrTargetType): String =
        when {
            !targetType.isTargetTypeCastRequired -> "this"
            targetType.isObjectCastRequired -> "(${T(targetType.typeName)}) (${T<Any>()}) this"
            else -> "(${T(targetType.typeName)}) this"
        }

    private fun PoetesseFile.writeWith(aggregating: Boolean, originatingFiles: Iterable<KSFile>) {
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating, *originatingFiles.toList().toTypedArray()),
            packageName = packageName.orEmpty(),
            fileName = fileName,
            extensionName = extensionName,
        ).writer().use(::writeTo)
    }

    private fun generateMixinConfig(mixinBlueprints: List<IrMixin>) {
        generateResourceFile(
            "generated-mixins.json",
            mixinBlueprints.mapNotNull { it.patchOriginatingFile },
            aggregating = true,
        ) {
            val envClassNames = mixinBlueprints.groupBy({ it.env }, { it.className })
            Json.encodeToString(GeneratedMixinsJson.of(kspOptions.mixinPackage, envClassNames))
        }
    }

    private fun generateResourceFile(
        fileName: String,
        originatingFiles: Iterable<KSFile>,
        aggregating: Boolean,
        buildText: () -> String,
    ) {
        codeGenerator.createNewFileByPath(
            dependencies = Dependencies(aggregating, *originatingFiles.toList().toTypedArray()),
            path = "lapis-intermediates/$fileName",
            extensionName = "",
        ).writer().use { it.write(buildText()) }
    }
}
