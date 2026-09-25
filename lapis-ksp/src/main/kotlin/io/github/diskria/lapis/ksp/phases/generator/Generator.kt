package io.github.diskria.lapis.ksp.phases.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.extensions.qualifiedNameOf
import io.github.diskria.lapis.ksp.phases.generator.models.GeneratedMixinsJson
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.annotation.processing.Generated

class Generator(
    private val options: KspOptions,
    private val poetesse: Poetesse,
    private val codeGenerator: CodeGenerator,
) {
    private val generatedDate: String by lazy {
        OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"))
    }

    fun generate(patches: List<FirPatch>) {
        patches.forEach { patch ->
            patch.mixin.duck?.let {
                generateMixinDuck(it, patch as? FirPatchInterface)
                generateExtensions(it, patch)
            }
            when (patch) {
                is FirPatchClass -> {
                    patch.impl?.let { generatePatchImpl(it, patch) }
                    generateMixinClass(patch.mixin, patch)
                }

                is FirPatchInterface -> {
                    generateMixinInterface(patch.mixin, patch)
                }
            }
        }
        generateMixinConfig(patches.map { it.mixin })
    }

    private fun generateMixinDuck(duck: IrMixinDuck, patchInterface: FirPatchInterface?) {
        poetesse {
            java.file(duck.className) {
                interface_(fileName) { _ ->
                    generatedMarker("Duck interface for binding shadows and forwarding extensions")
                    suppressAllWarnings()
                    public()
                    duck.typeVariables.forEach { +it }
                    patchInterface?.let { superinterface(it.className) }
                    duck.shadows.forEach { shadow ->
                        shadow.kinds.forEach { kind ->
                            val prefixedMethod = method(kind.name) {
                                public()
                                abstract()
                                shadow.typeVariables.forEach { +it }
                                kind.parameters.forEach { parameter(it.name, it.typeName) }
                                kind.returnTypeName?.let { returns(it) }
                            }
                            if (patchInterface != null) {
                                method(kind.sourceJvmName) {
                                    annotation<Override>()
                                    public()
                                    default()
                                    shadow.typeVariables.forEach { +it }
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
                                extension.typeVariables.forEach { +it }
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

    // TODO: Migrate to FIR plugin
    private fun generateExtensions(duck: IrMixinDuck, patch: FirPatch) {
        val extensions = duck.extensions.ifEmpty { return }
        poetesse {
            kotlin.file(patch.className.withSuffix("_Extensions")) {
                generatedMarker(
                    "Kotlin sugar providing zero-boilerplate access to the forwarded extensions in duck interface"
                )
                suppressAllWarnings()
                val duckClassName = duck.className.optionalGeneric(duck.typeVariables)
                extensions.forEach { extension ->
                    when (extension) {
                        is IrMixinDuck.Extension.Property -> property(extension.sourceName, extension.typeName) {
                            public()
                            inline()
                            duck.typeVariables.forEach { +it }
                            extension.typeVariables.forEach { +it }
                            extensionReceiver(extension.receiverTargetTypeCast.typeName)
                            getter {
                                expression {
                                    "(this as ${T(duckClassName)}).${(N(extension.getter.name))}()"
                                }
                            }
                            extension.setter?.let { setter ->
                                setter { newValue ->
                                    body {
                                        line { "(this as ${T(duckClassName)}).${N(setter.name)}(${N(newValue)})" }
                                    }
                                }
                            }
                        }

                        is IrMixinDuck.Extension.Function -> function(extension.sourceName) {
                            public()
                            inline()
                            duck.typeVariables.forEach { +it }
                            extension.typeVariables.forEach { +it }
                            extensionReceiver(extension.receiverTargetTypeCast.typeName)
                            extension.parameters.forEach { parameter(it.name, it.typeName) }
                            extension.returnTypeName?.let { returns(it) }
                            body {
                                val maybeReturn = if (extension.returnTypeName != null) "return " else ""
                                val parameters = code { extension.parameters.joinToString { N(it.name) } }
                                line {
                                    "$maybeReturn(this as ${T(duckClassName)}).${N(extension.name)}(${L(parameters)})"
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(duck.patchOriginatingFile))
    }

    private fun generatePatchImpl(patchImpl: IrPatchImpl, patch: FirPatchClass) {
        poetesse {
            kotlin.file(patchImpl.className) {
                generatedMarker("KMixin implementation for binding shadows")
                suppressAllWarnings()
                class_(fileName) { _ ->
                    public()
                    patchImpl.typeVariables.forEach { +it }
                    if (patchImpl.constructorParameters.isNotEmpty()) {
                        constructor(primary = true) {
                            public()
                            patchImpl.constructorParameters.forEach { parameter ->
                                when (parameter) {
                                    is IrPatchImpl.ConstructorParameter.Instance -> {
                                        parameter(parameter.name, parameter.targetTypeCast.typeName)
                                    }

                                    is IrPatchImpl.ConstructorParameter.Duck -> {
                                        parameter("duck", parameter.className.optionalGeneric(patchImpl.typeVariables))
                                            .property { private() }
                                    }
                                }
                            }
                        }
                    }
                    superclass(patch.className.optionalGeneric(patchImpl.typeVariables)) {
                        patch.constructorParameters.forEach { parameter ->
                            argument {
                                when (parameter) {
                                    is FirPatchClass.ConstructorParameter.Origin -> N(parameter.name)
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
                                    shadow.typeVariables.forEach { +it }
                                    shadow.parameters.forEach { parameter(it.name, it.typeName) }
                                    shadow.returnTypeName?.let { returns(it) }
                                    body {
                                        val maybeReturn = if (shadow.returnTypeName != null) "return " else ""
                                        val parameters = code { shadow.parameters.joinToString { N(it.name) } }
                                        val typeArgs = if (shadow.typeVariables.isNotEmpty()) {
                                            "<" + shadow.typeVariables.joinToString { it.name } + ">"
                                        } else ""
                                        line { "$maybeReturn${N("duck")}.${N(shadow.name)}$typeArgs(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(patchImpl.patchOriginatingFile))
    }

    private fun generateMixinClass(mixin: IrMixin, patch: FirPatchClass) {
        poetesse {
            java.file(mixin.className) {
                class_(fileName) { _ ->
                    public()
                    abstract()
                    mixin.typeVariables.forEach { +it }
                    generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                    suppressAllWarnings()
                    mixinAnnotations(mixin.annotations)
                    mixin.duck?.let { superinterface(it.className.optionalGeneric(mixin.typeVariables)) }
                    val extensions = mixin.duck?.extensions.orEmpty()
                    val memberInjections = mixin.injections.filterIsInstance<IrMixin.MemberInjection>()
                    val patchMember = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
                        patchMember(patch)
                    } else null
                    mixin.duck?.shadows?.forEach { shadow ->
                        when (shadow) {
                            is IrMixinDuck.Shadow.Property -> {
                                val shadowField = field(shadow.mappingName, shadow.typeName) {
                                    mixinAnnotations(shadow.mixinAnnotations)
                                    shadow.modifiers.forEach { modifier(it) }
                                }
                                shadow.kinds.forEach { kind ->
                                    method(kind.name) {
                                        annotation<Override>()
                                        public()
                                        shadow.typeVariables.forEach { +it }
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
                                    mixinAnnotations(shadow.mixinAnnotations)
                                    shadow.typeVariables.forEach { +it }
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
                                    shadow.typeVariables.forEach { +it }
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
                                    extension.typeVariables.forEach { +it }
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

    private fun JavaCodeScope.patchInitializer(patch: FirPatchClass): String {
        val (className, arguments) = if (patch.impl != null) {
            patch.impl.className to code {
                patch.impl.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is IrPatchImpl.ConstructorParameter.Instance -> targetTypeCast(parameter.targetTypeCast)
                        is IrPatchImpl.ConstructorParameter.Duck -> "this"
                    }
                }
            }
        } else {
            patch.className to code {
                patch.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is FirPatchClass.ConstructorParameter.Origin -> targetTypeCast(parameter.targetTypeCast)
                    }
                }
            }
        }
        val diamond = if (patch.typeVariables.isNotEmpty()) "<>" else ""
        return "new ${T(className)}$diamond(${L(arguments)})"
    }

    private fun JavaTypeScope.patchMember(patch: FirPatchClass): String {
        val isEager = patch.initStrategy == InitStrategy.Eager
        val isSynchronized = patch.initStrategy == InitStrategy.Synchronized
        val isThreadSafe = patch.initStrategy == InitStrategy.Volatile || isSynchronized
        val initializer = poetesse.java.code { patchInitializer(patch) }
        val patchField = field("patch", patch.className.optionalGeneric(patch.typeVariables, !isEager)) {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
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
                annotation<Annotation>(xClass(options.uniqueAnnotation))
                initializer { "new ${T<Any>()}()" }
            }
        } else null
        val getOrInitPatchMethod = method("getOrInitPatch") {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
            body {
                if (isThreadSafe) {
                    val localType = patch.className.optionalGeneric(patch.typeVariables, nullable = true)
                    val local by var_(localType) { "this.${N(patchField)}" }
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
            returns(patch.className.optionalGeneric(patch.typeVariables))
        }
        return "$getOrInitPatchMethod()"
    }

    private fun generateMixinInterface(mixin: IrMixin, patch: FirPatchInterface) {
        poetesse {
            java.file(mixin.className) {
                interface_(fileName) { _ ->
                    public()
                    generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                    suppressAllWarnings()
                    mixinAnnotations(mixin.annotations)
                    superinterface(mixin.duck?.className ?: patch.className)
                    mixin.duck?.shadows?.filterIsInstance<IrMixinDuck.Shadow.Function>()?.forEach { shadowFunction ->
                        val shadowMethod = method("shadow$${shadowFunction.mappingName}") {
                            mixinAnnotations(shadowFunction.mixinAnnotations)
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
            injection.typeVariables.forEach { +it }
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
                            injection.extensionReceiverTargetTypeCast?.let { add(targetTypeCast(it)) }
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
        poetesse.java.annotation(annotation.className) {
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
            is IrMixinAnnotation.Argument.EnumValue -> "${T(value.enumClassName)}.${N(value.entryName)}"
            is IrMixinAnnotation.Argument.ClassValue -> "${T(value.className)}.class"
            is IrMixinAnnotation.Argument.AnnotationValue -> L(mixinAnnotation(value.annotation))
        }

    private fun JavaCodeScope.targetTypeCast(cast: IrTargetSubtypeCast): String =
        when {
            !cast.isTargetCastRequired -> "this"
            cast.isUnsafeCastRequired -> "(${T(cast.typeName)}) (${T<Any>()}) this"
            else -> "(${T(cast.typeName)}) this"
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
            val classNames = mixinBlueprints.groupBy({ it.side }, { it.className })
            Json.encodeToString(GeneratedMixinsJson.of(options.mixinPackage, classNames))
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

    private fun JavaTypeScope.generatedMarker(roleDesc: String) {
        annotation<Generated> {
            member(Generated::value, qualifiedNameOf<Generator>())
            member(Generated::date, generatedDate)
            member(Generated::comments, roleDesc)
        }
    }

    private fun KotlinFileScope.generatedMarker(roleDesc: String) {
        annotation<Generated> {
            member(Generated::value, qualifiedNameOf<Generator>())
            member(Generated::date, generatedDate)
            member(Generated::comments, roleDesc)
        }
    }
}

private fun XClassName.optionalGeneric(typeVariables: List<XTypeName>, nullable: Boolean = false): XTypeName =
    if (typeVariables.isNotEmpty()) generic(typeVariables, nullable = nullable)
    else nullable(nullable)

private fun JavaTypeScope.suppressAllWarnings() {
    annotation<SuppressWarnings> {
        member(SuppressWarnings::value, "ALL")
    }
}

private fun KotlinFileScope.suppressAllWarnings() {
    annotation<Suppress> {
        member(Suppress::names, "warnings")
    }
}
