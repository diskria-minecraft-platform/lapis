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

    private fun generateMixinDuck(duck: IrMixinDuck, kMixinInterface: FirKMixinInterface?) = poetesse {
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
                            if (shadow is IrMixinDuck.Shadow.Function) {
                                shadow.typeVariables.forEach { +it }
                            }
                            kind.returnType?.let { returns(it.java) }
                            kind.parameters.forEach { parameter(it.name, it.type.java) }
                        }
                        if (kMixinInterface != null) {
                            method(kind.sourceJvmName) {
                                annotation<Override>()
                                public()
                                default()
                                if (shadow is IrMixinDuck.Shadow.Function) {
                                    shadow.typeVariables.forEach { +it }
                                }
                                kind.returnType?.let { returns(it.java) }
                                kind.parameters.forEach { parameter(it.name, it.type.java) }
                                body {
                                    val returner = if (kind.returnType != null) "return " else ""
                                    val callable = code { N(prefixedMethod) }
                                    val arguments = code { kind.parameters.joinToString { N(it.name) } }
                                    line { "$returner${L(callable)}(${L(arguments)})" }
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
                            if (extension is IrMixinDuck.Extension.Function) {
                                extension.typeVariables.forEach { +it }
                            }
                            kind.returnType?.let { returns(it.java) }
                            kind.parameters.forEach { parameter(it.name, it.type.java) }
                            if (kMixinInterface != null) {
                                body {
                                    val returner = if (kind.returnType != null) "return " else ""
                                    val receiver = code { "${T(kMixinInterface.className)}.super" }
                                    val callable = code { N(kind.sourceJvmName) }
                                    val arguments = code { kind.parameters.joinToString { N(it.name) } }
                                    line { "$returner${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(duck.originatingFile))

    // TODO: Migrate to FIR plugin
    private fun generateExtensions(duck: IrMixinDuck, kMixin: FirKMixin): Unit = poetesse {
        val extensions = duck.extensions.ifEmpty { return }
        val duckClassName = duck.className.optionalGeneric(duck.typeVariables)
        val receiver = kotlin.code { "(this as ${T(duckClassName)})" }
        kotlin.file(kMixin.className.withSuffix("_Extensions")) {
            generatedMarker(
                "Kotlin sugar providing zero-boilerplate access to the forwarded extensions in duck interface"
            )
            suppressAllWarnings()
            extensions.forEach { extension ->
                when (extension) {
                    is IrMixinDuck.Extension.Property -> property(extension.sourceName, extension.type.kotlin) {
                        public()
                        inline()
                        extensionReceiver(extension.receiverType.kotlin)
                        getter {
                            expression {
                                val callable = code { N(extension.getter.name) }
                                "${L(receiver)}.${L(callable)}()"
                            }
                        }
                        extension.setter?.let { setter ->
                            setter(setter.parameter.name) { newValue ->
                                body {
                                    val callable = code { N(setter.name) }
                                    val arguments = code { N(newValue) }
                                    line { "${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }

                    is IrMixinDuck.Extension.Function -> function(extension.sourceName) {
                        public()
                        inline()
                        duck.typeVariables.forEach { +it }
                        extension.typeVariables.forEach { +it }
                        extensionReceiver(extension.receiverType.kotlin)
                        extension.parameters.forEach { parameter(it.name, it.type.kotlin) }
                        extension.returnType?.let { returns(it.kotlin) }
                        body {
                            val returner = if (extension.returnType != null) "return " else ""
                            val callable = code { N(extension.name) }
                            val generics = generics(extension.typeVariables)
                            val arguments = code { extension.parameters.joinToString { N(it.name) } }
                            line { "$returner${L(receiver)}.${L(callable)}${L(generics)}(${L(arguments)})" }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(duck.originatingFile))

    private fun generateKMixinImpl(kMixinImpl: IrKMixinImpl, kMixin: FirKMixinClass) = poetesse {
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
                        is IrMixinDuck.Property -> property(shadow.sourceName, shadow.type.kotlin) {
                            public()
                            override()
                            getter {
                                expression {
                                    val receiver = code { N("duck") }
                                    val callable = code { N(shadow.getter.name) }
                                    "${L(receiver)}.${L(callable)}()"
                                }
                            }
                            shadow.setter?.let { setter ->
                                setter(setter.parameter.name) { newValue ->
                                    body {
                                        val receiver = code { N("duck") }
                                        val callable = code { N(setter.name) }
                                        val arguments = code { N(newValue) }
                                        line { "${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                    }
                                }
                            }
                        }

                        is IrMixinDuck.Function -> function(shadow.sourceName) {
                            public()
                            override()
                            shadow.typeVariables.forEach { +it }
                            shadow.parameters.forEach { parameter(it.name, it.type.kotlin) }
                            shadow.returnType?.let { returns(it.kotlin) }
                            body {
                                val returner = if (shadow.returnType != null) "return " else ""
                                val receiver = code { N("duck") }
                                val callable = code { N(shadow.name) }
                                val generics = generics(shadow.typeVariables)
                                val arguments = code { shadow.parameters.joinToString { N(it.name) } }
                                line { "$returner${L(receiver)}.${L(callable)}${L(generics)}(${L(arguments)})" }
                            }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(kMixinImpl.originatingFile))

    private fun generateMixinClass(mixin: IrMixin, kMixin: FirKMixinClass) = poetesse {
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
                val delegate = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
                    delegateMember(kMixin)
                } else null
                mixin.duck?.shadows?.forEach { shadow ->
                    when (shadow) {
                        is IrMixinDuck.Shadow.Property -> {
                            val shadowField = field(shadow.mappingName, shadow.type.java) {
                                mixinAnnotations(shadow.mixinAnnotations)
                                shadow.modifiers.forEach { +it }
                            }
                            shadow.kinds.forEach { kind ->
                                method(kind.name) {
                                    annotation<Override>()
                                    public()
                                    kind.returnType?.let { returns(it.java) }
                                    kind.parameters.forEach { parameter(it.name, it.type.java) }
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
                                shadow.modifiers.forEach { +it }
                                shadow.typeVariables.forEach { +it }
                                shadow.returnType?.let { returns(it.java) }
                                shadow.parameters.forEach { parameter(it.name, it.type.java) }
                                if (JPModifier.STATIC in shadow.modifiers) {
                                    body { line { "throw new ${T<AssertionError>()}(${S("Stub!")})" } }
                                }
                            }
                            method(shadow.name) {
                                annotation<Override>()
                                public()
                                shadow.typeVariables.forEach { +it }
                                shadow.returnType?.let { returns(it.java) }
                                shadow.parameters.forEach { parameter(it.name, it.type.java) }
                                body {
                                    val returner = if (shadow.returnType != null) "return " else ""
                                    val callable = code { N(shadowMethod) }
                                    val arguments = code { shadow.parameters.joinToString { N(it.name) } }
                                    line { "$returner${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                }
                if (delegate != null) {
                    extensions.forEach { extension ->
                        extension.kinds.forEach { kind ->
                            method(kind.name) {
                                annotation<Override>()
                                public()
                                if (extension is IrMixinDuck.Extension.Function) {
                                    extension.typeVariables.forEach { +it }
                                }
                                kind.returnType?.let { returns(it.java) }
                                kind.parameters.forEach { parameter(it.name, it.type.java) }
                                body {
                                    val returner = if (kind.returnType != null) "return " else ""
                                    val receiver = code { N(delegate) }
                                    val callable = code { N(kind.sourceJvmName) }
                                    val arguments = code { kind.parameters.joinToString { N(it.name) } }
                                    line { "$returner${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                    memberInjections.forEach { mixinInjection(it) { delegate } }
                }
                mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                    mixinInjection(staticInjection) {
                        "${T(kMixin.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(mixin.originatingFile))

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
        val generics = generics(kMixin.typeVariables, diamond = true)
        return "new ${T(className)}${L(generics)}(${L(arguments)})"
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

    private fun generateMixinInterface(mixin: IrMixin, kMixinInterface: FirKMixinInterface) = poetesse {
        java.file(mixin.className) {
            interface_(fileName) { _ ->
                generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                suppressAllWarnings()
                mixinAnnotations(mixin.annotations)
                public()
                superinterface(mixin.duck?.className ?: kMixinInterface.className)
                mixin.duck?.shadows?.filterIsInstance<IrMixinDuck.Shadow.Function>()?.forEach { shadow ->
                    val shadowMethod = method("shadow$${shadow.mappingName}") {
                        mixinAnnotations(shadow.mixinAnnotations)
                        shadow.modifiers.forEach { +it }
                        shadow.returnType?.let { returns(it.java) }
                        shadow.parameters.forEach { parameter(it.name, it.type.java) }
                        if (JPModifier.STATIC in shadow.modifiers || JPModifier.PRIVATE in shadow.modifiers) {
                            body {
                                line { "throw new ${T<AssertionError>()}(${S("Stub!")})" }
                            }
                        }
                    }
                    method(shadow.name) {
                        annotation<Override>()
                        public()
                        default()
                        shadow.returnType?.let { returns(it.java) }
                        shadow.parameters.forEach { parameter(it.name, it.type.java) }
                        body {
                            val returner = if (shadow.returnType != null) "return " else ""
                            val callable = code { N(shadowMethod) }
                            val arguments = code { shadow.parameters.joinToString { N(it.name) } }
                            line { "$returner${L(callable)}(${L(arguments)})" }
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

    private fun JavaTypeScope.mixinInjection(injection: IrMixin.Injection, receiver: JavaCodeScope.() -> String) {
        method(injection.name) {
            mixinAnnotations(injection.mixinAnnotations)
            private()
            if (injection is IrMixin.StaticInjection) static()
            injection.typeVariables.forEach { +it }
            injection.returnType?.let { returns(it.java) }
            injection.parameters.forEach { parameter ->
                parameter(parameter.name, parameter.type.java) {
                    mixinAnnotations(parameter.mixinAnnotations)
                }
            }
            body {
                val returner = if (injection.returnType != null) "return " else ""
                val callable = code { N(injection.sourceJvmName) }
                val arguments = code {
                    buildList {
                        if (injection is IrMixin.MemberInjection) {
                            injection.extensionReceiverTargetTypeCast?.let { add(targetTypeCast(it)) }
                        }
                        addAll(injection.parameters.map { N(it.name) })
                    }.joinToString()
                }
                line { "$returner${L(receiver)}.${L(callable)}(${L(arguments)})" }
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

    private fun generateMixinConfig(mixins: List<IrMixin>) {
        generateResourceFile("generated-mixins.json", mixins.mapNotNull { it.originatingFile }, aggregating = true) {
            val classNames = mixins.groupBy({ it.side }, { it.className })
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

private fun JavaCodeFactory.generics(typeVariables: List<XTypeVariableName>, diamond: Boolean = false): JavaCodeRef =
    code {
        if (typeVariables.isNotEmpty()) {
            if (diamond) "<>"
            else "<" + typeVariables.joinToString { N(it.name) } + ">"
        } else ""
    }

private fun KotlinCodeFactory.generics(typeVariables: List<XTypeVariableName>): KotlinCodeRef =
    code {
        if (typeVariables.isNotEmpty()) {
            "<" + typeVariables.joinToString { N(it.name) } + ">"
        } else ""
    }

private fun XClassName.optionalGeneric(typeVariables: List<XTypeName>, nullable: Boolean = false): XTypeName =
    if (typeVariables.isNotEmpty()) generic(typeVariables, nullable = nullable)
    else nullable(nullable)
