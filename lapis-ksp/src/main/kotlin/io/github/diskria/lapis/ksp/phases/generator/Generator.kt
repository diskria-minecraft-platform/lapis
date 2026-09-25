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

    fun generate(kMixins: List<FirKMixin>) {
        kMixins.forEach { kMixin ->
            kMixin.mixin.duck?.let {
                generateMixinDuck(it, kMixin as? FirKMixinInterface)
                generateExtensions(it, kMixin)
            }
            when (kMixin) {
                is FirKMixinClass -> {
                    kMixin.impl?.let { generateKMixinImpl(it, kMixin) }
                    generateMixinClass(kMixin.mixin, kMixin)
                }

                is FirKMixinInterface -> {
                    generateMixinInterface(kMixin.mixin, kMixin)
                }
            }
        }
        generateMixinConfig(kMixins.map { it.mixin })
    }

    private fun generateMixinDuck(duck: IrMixinDuck, kMixinInterface: FirKMixinInterface?) {
        poetesse {
            java.file(duck.className) {
                interface_(fileName) { _ ->
                    generatedMarker("Duck interface for binding Mixin shadows and forwarding extensions")
                    suppressAllWarnings()
                    public()
                    duck.typeVariables.forEach { +it }
                    kMixinInterface?.let { superinterface(it.className) }
                    duck.shadows.forEach { shadow ->
                        shadow.kinds.forEach { kind ->
                            val prefixedMethod = method(kind.name) {
                                public()
                                abstract()
                                shadow.typeVariables.forEach { +it }
                                kind.parameters.forEach { parameter(it.name, it.type.java) }
                                kind.returnType?.let { returns(it.java) }
                            }
                            if (kMixinInterface != null) {
                                method(kind.sourceJvmName) {
                                    annotation<Override>()
                                    public()
                                    default()
                                    shadow.typeVariables.forEach { +it }
                                    kind.parameters.forEach { parameter(it.name, it.type.java) }
                                    kind.returnType?.let { returns(it.java) }
                                    body {
                                        val maybeReturn = if (kind.returnType != null) "return " else ""
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
                                if (kMixinInterface != null) default() else abstract()
                                extension.typeVariables.forEach { +it }
                                kind.parameters.forEach { parameter(it.name, it.type.java) }
                                kind.returnType?.let { returns(it.java) }
                                if (kMixinInterface != null) {
                                    body {
                                        val ret = if (kind.returnType != null) "return " else ""
                                        val rec = code { "${T(kMixinInterface.className)}.super" }
                                        val params = code { kind.parameters.joinToString { N(it.name) } }
                                        line {
                                            "$ret${L(rec)}.${N(kind.sourceJvmName)}(${L(params)})"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(duck.originatingFile))
    }

    // TODO: Migrate to FIR plugin
    private fun generateExtensions(duck: IrMixinDuck, kMixin: FirKMixin) {
        val extensions = duck.extensions.ifEmpty { return }
        poetesse {
            kotlin.file(kMixin.className.withSuffix("_Extensions")) {
                generatedMarker(
                    "Kotlin sugar providing zero-boilerplate access to the forwarded extensions in duck interface"
                )
                suppressAllWarnings()
                val duckClassName = duck.className.optionalGeneric(duck.typeVariables)
                extensions.forEach { extension ->
                    when (extension) {
                        is IrMixinDuck.Extension.Property -> property(extension.sourceName, extension.type.kotlin) {
                            public()
                            inline()
                            duck.typeVariables.forEach { +it }
                            extension.typeVariables.forEach { +it }
                            extensionReceiver(extension.receiverTargetTypeCast.type.kotlin)
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
                            extensionReceiver(extension.receiverTargetTypeCast.type.kotlin)
                            extension.parameters.forEach { parameter(it.name, it.type.kotlin) }
                            extension.returnType?.let { returns(it.kotlin) }
                            body {
                                val maybeReturn = if (extension.returnType != null) "return " else ""
                                val parameters = code { extension.parameters.joinToString { N(it.name) } }
                                line {
                                    "$maybeReturn(this as ${T(duckClassName)}).${N(extension.name)}(${L(parameters)})"
                                }
                            }
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(duck.originatingFile))
    }

    private fun generateKMixinImpl(kMixinImpl: IrKMixinImpl, kMixin: FirKMixinClass) {
        poetesse {
            kotlin.file(kMixinImpl.className) {
                generatedMarker("KMixin implementation for binding Mixin shadows")
                suppressAllWarnings()
                class_(fileName) { _ ->
                    public()
                    kMixinImpl.typeVariables.forEach { +it }
                    if (kMixinImpl.constructorParameters.isNotEmpty()) {
                        constructor(primary = true) {
                            public()
                            kMixinImpl.constructorParameters.forEach { parameter ->
                                when (parameter) {
                                    is IrKMixinImpl.ConstructorParameter.Instance -> {
                                        parameter(parameter.name, parameter.targetTypeCast.type.kotlin)
                                    }

                                    is IrKMixinImpl.ConstructorParameter.Duck -> {
                                        parameter("duck", parameter.className.optionalGeneric(kMixinImpl.typeVariables))
                                            .property { private() }
                                    }
                                }
                            }
                        }
                    }
                    superclass(kMixin.className.optionalGeneric(kMixinImpl.typeVariables)) {
                        kMixin.constructorParameters.forEach { parameter ->
                            argument {
                                when (parameter) {
                                    is FirKMixinClass.ConstructorParameter.Origin -> N(parameter.name)
                                }
                            }
                        }
                    }
                    kMixin.mixin.duck?.shadows?.forEach { shadow ->
                        when (shadow) {
                            is IrMixinDuck.Property -> {
                                property(shadow.sourceName, shadow.type.kotlin) {
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
                                    shadow.parameters.forEach { parameter(it.name, it.type.kotlin) }
                                    shadow.returnType?.let { returns(it.kotlin) }
                                    body {
                                        val maybeReturn = if (shadow.returnType != null) "return " else ""
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
        }.writeWith(aggregating = false, listOfNotNull(kMixinImpl.originatingFile))
    }

    private fun generateMixinClass(mixin: IrMixin, kMixin: FirKMixinClass) {
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
                    val delegateMember = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
                        delegateMember(kMixin)
                    } else null
                    mixin.duck?.shadows?.forEach { shadow ->
                        when (shadow) {
                            is IrMixinDuck.Shadow.Property -> {
                                val shadowField = field(shadow.mappingName, shadow.type.java) {
                                    mixinAnnotations(shadow.mixinAnnotations)
                                    shadow.modifiers.forEach { modifier(it) }
                                }
                                shadow.kinds.forEach { kind ->
                                    method(kind.name) {
                                        annotation<Override>()
                                        public()
                                        shadow.typeVariables.forEach { +it }
                                        kind.parameters.forEach { parameter(it.name, it.type.java) }
                                        kind.returnType?.let { returns(it.java) }
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
                                    shadow.parameters.forEach { parameter(it.name, it.type.java) }
                                    shadow.returnType?.let { returns(it.java) }
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
                                    shadow.parameters.forEach { parameter(it.name, it.type.java) }
                                    shadow.returnType?.let { returns(it.java) }
                                    body {
                                        val maybeReturn = if (shadow.returnType != null) "return " else ""
                                        val parameters = code { shadow.parameters.joinToString { N(it.name) } }
                                        line { "$maybeReturn${N(shadowMethod)}(${L(parameters)})" }
                                    }
                                }
                            }
                        }
                    }
                    if (delegateMember != null) {
                        extensions.forEach { extension ->
                            extension.kinds.forEach { kind ->
                                method(kind.name) {
                                    annotation<Override>()
                                    public()
                                    extension.typeVariables.forEach { +it }
                                    kind.parameters.forEach { parameter(it.name, it.type.java) }
                                    kind.returnType?.let { returns(it.java) }
                                    body {
                                        val ret = if (kind.returnType != null) "return " else ""
                                        val params = code { kind.parameters.joinToString { N(it.name) } }
                                        line {
                                            "$ret${N(delegateMember)}.${N(kind.sourceJvmName)}(${L(params)})"
                                        }
                                    }
                                }
                            }
                        }
                        memberInjections.forEach { mixinInjection(it) { delegateMember } }
                    }
                    mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                        mixinInjection(staticInjection) {
                            "${T(kMixin.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(mixin.originatingFile))
    }

    private fun JavaCodeScope.delegateInitializer(kMixin: FirKMixinClass): String {
        val (className, arguments) = if (kMixin.impl != null) {
            kMixin.impl.className to code {
                kMixin.impl.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is IrKMixinImpl.ConstructorParameter.Instance -> targetTypeCast(parameter.targetTypeCast)
                        is IrKMixinImpl.ConstructorParameter.Duck -> "this"
                    }
                }
            }
        } else {
            kMixin.className to code {
                kMixin.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is FirKMixinClass.ConstructorParameter.Origin -> targetTypeCast(parameter.targetTypeCast)
                    }
                }
            }
        }
        val diamond = if (kMixin.typeVariables.isNotEmpty()) "<>" else ""
        return "new ${T(className)}$diamond(${L(arguments)})"
    }

    private fun JavaTypeScope.delegateMember(kMixin: FirKMixinClass): String {
        val isEager = kMixin.initStrategy == InitStrategy.Eager
        val isSynchronized = kMixin.initStrategy == InitStrategy.Synchronized
        val isThreadSafe = kMixin.initStrategy == InitStrategy.Volatile || isSynchronized
        val initializer = poetesse.java.code { delegateInitializer(kMixin) }
        val delegateField = field("delegate", kMixin.className.optionalGeneric(kMixin.typeVariables, !isEager)) {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
            if (isEager) {
                final()
                initializer(initializer)
            } else if (isThreadSafe) {
                volatile()
            }
        }
        if (isEager) return delegateField
        val delegateLockField = if (isSynchronized) {
            field<Any>("delegateLock") {
                private()
                final()
                annotation<Annotation>(xClass(options.uniqueAnnotation))
                initializer { "new ${T<Any>()}()" }
            }
        } else null
        val getDelegate by method {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
            body {
                if (isThreadSafe) {
                    val localType = kMixin.className.optionalGeneric(kMixin.typeVariables, nullable = true)
                    val local by var_(localType) { "this.${N(delegateField)}" }
                    controlFlow {
                        branch({ "if (${N(local)} == null)" }) {
                            if (isSynchronized && delegateLockField != null) {
                                controlFlow {
                                    branch({ "synchronized (this.${N(delegateLockField)})" }) {
                                        line { "${N(local)} = this.${N(delegateField)}" }
                                        controlFlow {
                                            branch({ "if (${N(local)} == null)" }) {
                                                line { "${N(local)} = ${L(initializer)}" }
                                                line { "this.${N(delegateField)} = ${N(local)}" }
                                            }
                                        }
                                    }
                                }
                            } else {
                                line { "${N(local)} = ${L(initializer)}" }
                                line { "this.${N(delegateField)} = ${N(local)}" }
                            }
                        }
                    }
                    line { "return ${N(local)}" }
                } else {
                    controlFlow {
                        branch({ "if (this.${N(delegateField)} == null)" }) {
                            line { "this.${N(delegateField)} = ${L(initializer)}" }
                        }
                    }
                    line { "return this.${N(delegateField)}" }
                }
            }
            returns(kMixin.className.optionalGeneric(kMixin.typeVariables))
        }
        return "$getDelegate()"
    }

    private fun generateMixinInterface(mixin: IrMixin, kMixinInterface: FirKMixinInterface) {
        poetesse {
            java.file(mixin.className) {
                interface_(fileName) { _ ->
                    public()
                    generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                    suppressAllWarnings()
                    mixinAnnotations(mixin.annotations)
                    superinterface(mixin.duck?.className ?: kMixinInterface.className)
                    mixin.duck?.shadows?.filterIsInstance<IrMixinDuck.Shadow.Function>()?.forEach { shadowFunction ->
                        val shadowMethod = method("shadow$${shadowFunction.mappingName}") {
                            mixinAnnotations(shadowFunction.mixinAnnotations)
                            shadowFunction.modifiers.forEach { modifier(it) }
                            shadowFunction.parameters.forEach { parameter(it.name, it.type.java) }
                            shadowFunction.returnType?.let { returns(it.java) }
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
                            shadowFunction.parameters.forEach { parameter(it.name, it.type.java) }
                            shadowFunction.returnType?.let { returns(it.java) }
                            body {
                                val maybeReturn = if (shadowFunction.returnType != null) "return " else ""
                                val parameters = code { shadowFunction.parameters.joinToString { N(it.name) } }
                                line { "$maybeReturn${N(shadowMethod)}(${L(parameters)})" }
                            }
                        }
                    }
                    mixin.injections.filterIsInstance<IrMixin.MemberInjection>().forEach { memberInjection ->
                        mixinInjection(memberInjection) {
                            "${T(mixin.duck?.className ?: kMixinInterface.className)}.super"
                        }
                    }
                    mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                        mixinInjection(staticInjection) {
                            "${T(kMixinInterface.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                        }
                    }
                }
            }
        }.writeWith(aggregating = false, listOfNotNull(mixin.originatingFile))
    }

    private fun JavaTypeScope.mixinInjection(
        injection: IrMixin.Injection,
        delegateReceiver: JavaCodeScope.() -> String,
    ) {
        method(injection.name) {
            private()
            if (injection is IrMixin.StaticInjection) static()
            injection.typeVariables.forEach { +it }
            mixinAnnotations(injection.mixinAnnotations)
            injection.parameters.forEach { parameter ->
                parameter(parameter.name, parameter.type.java) {
                    mixinAnnotations(parameter.mixinAnnotations)
                }
            }
            injection.returnType?.let { returns(it.java) }
            body {
                val functionArguments = code {
                    buildList {
                        if (injection is IrMixin.MemberInjection) {
                            injection.extensionReceiverTargetTypeCast?.let { add(targetTypeCast(it)) }
                        }
                        addAll(injection.parameters.map { N(it.name) })
                    }.joinToString()
                }
                val maybeReturn = if (injection.returnType != null) "return " else ""
                line { "$maybeReturn${L(delegateReceiver)}.${N(injection.sourceJvmName)}(${L(functionArguments)})" }
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

    private fun JavaCodeScope.mixinAnnotationArgumentValue(value: IrMixinAnnotation.Argument.Value) = when (value) {
        is IrMixinAnnotation.Argument.BooleanValue -> L(value.boolean)
        is IrMixinAnnotation.Argument.ByteValue -> L(value.byte)
        is IrMixinAnnotation.Argument.ShortValue -> L(value.short)
        is IrMixinAnnotation.Argument.IntValue -> L(value.int)
        is IrMixinAnnotation.Argument.LongValue -> L(value.long)
        is IrMixinAnnotation.Argument.CharValue -> L(value.char)
        is IrMixinAnnotation.Argument.FloatValue -> L(value.float)
        is IrMixinAnnotation.Argument.DoubleValue -> L(value.double)
        is IrMixinAnnotation.Argument.StringValue -> S(value.string)
        is IrMixinAnnotation.Argument.EnumValue -> "${T(value.className)}.${N(value.name)}"
        is IrMixinAnnotation.Argument.ClassValue -> "${T(value.className)}.class"
        is IrMixinAnnotation.Argument.AnnotationValue -> L(mixinAnnotation(value.annotation))
    }

    private fun JavaCodeScope.targetTypeCast(cast: IrTargetSubtypeCast): String =
        when {
            !cast.isTargetCastRequired -> "this"
            cast.isUnsafeCastRequired -> "(${T(cast.type.java)}) (${T<Any>()}) this"
            else -> "(${T(cast.type.java)}) this"
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
            mixinBlueprints.mapNotNull { it.originatingFile },
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
