package io.github.diskria.lapis.ksp.phases.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.KspLogger
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.generator.models.GeneratedMixinsJson
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import org.spongepowered.asm.mixin.*

class Generator(
    private val kspOptions: KspOptions,
    private val poetesse: Poetesse,
    private val codeGenerator: CodeGenerator,
    @Suppress("unused") private val logger: KspLogger,
) {
    fun generate(patches: List<IrPatch>) {
        patches.forEach { patch ->
            patch.mixin.duck?.let {
                generateMixinDuck(it, patch as? IrPatchInterface)
                generateExtensions(it, patch)
            }
            when (patch) {
                is IrPatchClass -> {
                    patch.impl?.let { generatePatchImpl(it, patch) }
                    generateMixinClass(patch.mixin, patch)
                }

                is IrPatchInterface -> {
                    generateMixinInterface(patch.mixin, patch)
                }
            }
        }
        generateMixinConfig(patches.map { it.mixin })
    }

    private fun generateMixinDuck(duck: IrMixinDuck, patchInterface: IrPatchInterface?) {
        poetesse {
            java.file(duck.className) {
                interface_(fileName) { _ ->
                    public()
                    patchInterface?.let { superinterface(it.className) }
                    duck.shadows.forEach { shadow ->
                        shadow.kinds.forEach { kind ->
                            val prefixedMethod = method(kind.name) {
                                public()
                                abstract()
                                kind.parameters.forEach { parameter(it.name, it.typeName) }
                                kind.returnTypeName?.let { returns(it) }
                            }
                            if (patchInterface != null) {
                                method(kind.sourceJvmName) {
                                    annotation<Override>()
                                    public()
                                    default()
                                    kind.parameters.forEach { parameter(it.name, it.typeName) }
                                    kind.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (kind.returnTypeName != null) "return " else ""
                                        val parameters = code { kind.parameters.joinToString { N(it.name) } }
                                        line { "$maybeReturn${N(prefixedMethod)}(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                    duck.extensions.forEach { extension ->
                        extension.kinds.forEach { kind ->
                            method(kind.name) {
                                public()
                                if (patchInterface != null) default() else abstract()
                                kind.parameters.forEach { parameter(it.name, it.typeName) }
                                kind.returnTypeName?.let { returns(it) }
                                if (patchInterface != null) {
                                    body {
                                        val maybeReturn = if (kind.returnTypeName != null) "return " else ""
                                        val patchReceiver = code { "${T(patchInterface.className)}.super" }
                                        val parameters = code { kind.parameters.joinToString { N(it.name) } }
                                        line {
                                            "$maybeReturn${L(patchReceiver)}.${N(kind.sourceJvmName)}(${L(parameters)})"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(duck.patchOriginatingFile))
    }

    // TODO: migrate to FIR plugin
    private fun generateExtensions(duck: IrMixinDuck, patch: IrPatch) {
        val entries = duck.extensions.ifEmpty { return }
        poetesse {
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
        poetesse {
            kotlin.file(patchImpl.className) {
                class_(fileName) { _ ->
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
                    patch.mixin.duck?.shadows?.forEach { shadow ->
                        when (shadow) {
                            is IrMixinDuck.Property -> {
                                property(shadow.sourceName, shadow.typeName) {
                                    public()
                                    override()
                                    getter {
                                        expression {
                                            "${N("duck")}.${(N(shadow.getter.name))}()"
                                        }
                                    }
                                    shadow.setter?.let { setter ->
                                        setter { newValue ->
                                            body {
                                                line { "${N("duck")}.${N(setter.name)}(${N(newValue)})" }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrMixinDuck.Function -> {
                                function(shadow.sourceName) {
                                    public()
                                    override()
                                    shadow.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadow.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (shadow.returnTypeName != null) "return " else ""
                                        val parameters = code { shadow.parameters.joinToString { N(it.name) } }
                                        line { "$maybeReturn${N("duck")}.${N(shadow.name)}(${L(parameters)})" }
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
        poetesse {
            java.file(mixin.className) {
                class_(fileName) { _ ->
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
                    val extensions = mixin.duck?.extensions.orEmpty()
                    val memberInjections = mixin.injections.filterIsInstance<IrMixin.MemberInjection>()
                    val patchMember = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
                        patchMember(patch)
                    } else null
                    mixin.duck?.shadows?.forEach { shadow ->
                        when (shadow) {
                            is IrMixinDuck.Shadow.Property -> {
                                val shadowField = field(shadow.mappingName, shadow.typeName) {
                                    if (shadow.mixinAnnotations.isNotEmpty()) {
                                        mixinAnnotations(shadow.mixinAnnotations)
                                    } else {
                                        if (shadow.setter != null) annotation<Mutable>()
                                        if (shadow.isFinal) annotation<Final>()
                                        annotation<Shadow>()
                                    }
                                    shadow.modifiers.forEach { modifier(it) }
                                }
                                shadow.kinds.forEach { kind ->
                                    method(kind.name) {
                                        annotation<Override>()
                                        public()
                                        kind.parameters.forEach { parameter(it.name, it.typeName) }
                                        kind.returnTypeName?.let { returns(it) }
                                        body {
                                            when (kind) {
                                                is IrMixinDuck.Property.Getter -> {
                                                    line { "return ${N(shadowField)}" }
                                                }

                                                is IrMixinDuck.Property.Setter -> {
                                                    line { "${N(shadowField)} = ${kind.parameter.name}" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            is IrMixinDuck.Shadow.Function -> {
                                val shadowMethod = method(shadow.mappingName) {
                                    if (shadow.mixinAnnotations.isNotEmpty()) {
                                        mixinAnnotations(shadow.mixinAnnotations)
                                    } else {
                                        annotation<Shadow>()
                                    }
                                    shadow.modifiers.forEach { modifier(it) }
                                    shadow.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadow.returnTypeName?.let { returns(it) }
                                    if (JPModifier.STATIC in shadow.modifiers) {
                                        body {
                                            line { "throw new ${T<AssertionError>()}(${S("Stub!")})" }
                                        }
                                    }
                                }
                                method(shadow.name) {
                                    annotation<Override>()
                                    public()
                                    shadow.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadow.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (shadow.returnTypeName != null) "return " else ""
                                        val parameters = code { shadow.parameters.joinToString { N(it.name) } }
                                        line { "$maybeReturn${N(shadowMethod)}(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                    if (patchMember != null) {
                        extensions.forEach { extension ->
                            extension.kinds.forEach { kind ->
                                method(kind.name) {
                                    annotation<Override>()
                                    public()
                                    kind.parameters.forEach { parameter(it.name, it.typeName) }
                                    kind.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (kind.returnTypeName != null) "return " else ""
                                        val parameters = code { kind.parameters.joinToString { N(it.name) } }
                                        line {
                                            "$maybeReturn${N(patchMember)}.${N(kind.sourceJvmName)}(${L(parameters)})"
                                        }
                                    }
                                }
                            }
                        }
                        memberInjections.forEach { mixinInjection(it) { patchMember } }
                    }
                    mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                        mixinInjection(staticInjection) {
                            "${T(patch.className)}.${N(staticInjection.patchCompanionObjectName)}"
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
        val initializer = poetesse.java.code { patchInitializer(patch) }
        val patchField = field("patch", patch.className) {
            private()
            annotation<Unique>()
            if (isEager) {
                final()
                initializer(initializer)
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
                    val local by var_(patch.className) { "this.${N(patchField)}" }
                    controlFlow {
                        branch({ "if (${N(local)} == null)" }) {
                            if (isSynchronized && patchLockField != null) {
                                controlFlow {
                                    branch({ "synchronized (this.${N(patchLockField)})" }) {
                                        line { "${N(local)} = this.${N(patchField)}" }
                                        controlFlow {
                                            branch({ "if (${N(local)} == null)" }) {
                                                line { "${N(local)} = ${L(initializer)}" }
                                                line { "this.${N(patchField)} = ${N(local)}" }
                                            }
                                        }
                                    }
                                }
                            } else {
                                line { "${N(local)} = ${L(initializer)}" }
                                line { "this.${N(patchField)} = ${N(local)}" }
                            }
                        }
                    }
                    line { "return ${N(local)}" }
                } else {
                    controlFlow {
                        branch({ "if (this.${N(patchField)} == null)" }) {
                            line { "this.${N(patchField)} = ${L(initializer)}" }
                        }
                    }
                    line { "return this.${N(patchField)}" }
                }
            }
            returns(patch.className)
        }
        return "$getOrInitPatchMethod()"
    }

    private fun generateMixinInterface(mixin: IrMixin, patch: IrPatchInterface) {
        poetesse {
            java.file(mixin.className) {
                interface_(fileName) { _ ->
                    public()
                    if (mixin.annotations.isNotEmpty()) {
                        mixinAnnotations(mixin.annotations)
                    } else {
                        annotation<Mixin> {
                            member(Mixin::value, mixin.targetTypeName)
                        }
                    }
                    superinterface(mixin.duck?.className ?: patch.className)
                    mixin.duck?.shadows?.filterIsInstance<IrMixinDuck.Shadow.Function>()?.forEach { shadowFunction ->
                        val shadowMethod = method("shadow$${shadowFunction.mappingName}") {
                            if (shadowFunction.mixinAnnotations.isNotEmpty()) {
                                mixinAnnotations(shadowFunction.mixinAnnotations)
                            } else {
                                annotation<Shadow>()
                            }
                            shadowFunction.modifiers.forEach { modifier(it) }
                            shadowFunction.parameters.forEach { parameter(it.name, it.typeName) }
                            shadowFunction.returnTypeName?.let { returns(it) }
                            if (JPModifier.STATIC in shadowFunction.modifiers ||
                                JPModifier.PRIVATE in shadowFunction.modifiers
                            ) {
                                body {
                                    line { "throw new ${T<AssertionError>()}(${S("Stub!")})" }
                                }
                            }
                        }
                        method(shadowFunction.name) {
                            annotation<Override>()
                            public()
                            default()
                            shadowFunction.parameters.forEach { parameter(it.name, it.typeName) }
                            shadowFunction.returnTypeName?.let { returns(it) }
                            body {
                                val maybeReturn = if (shadowFunction.returnTypeName != null) "return " else ""
                                val parameters = code { shadowFunction.parameters.joinToString { N(it.name) } }
                                line { "$maybeReturn${N(shadowMethod)}(${L(parameters)})" }
                            }
                        }
                    }
                    mixin.injections.filterIsInstance<IrMixin.MemberInjection>().forEach { memberInjection ->
                        mixinInjection(memberInjection) {
                            "${T(mixin.duck?.className ?: patch.className)}.super"
                        }
                    }
                    mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                        mixinInjection(staticInjection) {
                            "${T(patch.className)}.${N(staticInjection.patchCompanionObjectName)}"
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(mixin.patchOriginatingFile))
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
                        addAll(injection.parameters.map { N(it.name) })
                    }.joinToString()
                }
                val maybeReturn = if (injection.returnTypeName != null) "return " else ""
                line { "$maybeReturn${L(patchReceiver)}.${N(injection.sourceJvmName)}(${L(functionArguments)})" }
            }
        }
    }

    private fun JavaAnnotationTrait.mixinAnnotations(mixinAnnotations: List<IrMixinAnnotation>) {
        mixinAnnotations.forEach { +mixinAnnotation(it) }
    }

    private fun mixinAnnotation(annotation: IrMixinAnnotation): JavaTypedAnnotationRef<Annotation> =
        poetesse.java.annotation(annotation.typeClassName) {
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
            is IrMixinAnnotation.Argument.EnumValue -> "${T(value.className)}.${N(value.entryName)}"
            is IrMixinAnnotation.Argument.TypeValue -> "${T(value.typeName)}.class"
            is IrMixinAnnotation.Argument.AnnotationValue -> L(mixinAnnotation(value.annotation))
        }

    private fun JavaCodeScope.targetTypeCast(targetRelatedType: IrTargetCompatType): String =
        when {
            !targetRelatedType.isTargetCastRequired -> "this"
            targetRelatedType.isUnsafeCastRequired -> "(${T(targetRelatedType.typeName)}) (${T<Any>()}) this"
            else -> "(${T(targetRelatedType.typeName)}) this"
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
