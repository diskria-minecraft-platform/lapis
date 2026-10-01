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
    private val kMixins: List<KMixinFir>,
    private val options: KspOptions,
    private val poetesse: Poetesse,
    private val codeGenerator: CodeGenerator,
) {
    private val generatedDate: String by lazy {
        OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX"))
    }

    fun generate() {
        kMixins.forEach { kMixin ->
            kMixin.mixin.duck?.let {
                generateMixinDuck(it, kMixin as? KMixinFirInterface)
                if (it.extensions.isNotEmpty()) {
                    generateExtensions(it, kMixin)
                }
            }
            when (kMixin) {
                is KMixinFirClass -> {
                    kMixin.impl?.let { generateKMixinImpl(it, kMixin) }
                    generateMixinClass(kMixin.mixin, kMixin)
                }

                is KMixinFirInterface -> {
                    generateMixinInterface(kMixin.mixin, kMixin)
                }
            }
        }
        generateMixinConfig(kMixins.map { it.mixin })
    }

    private fun generateMixinDuck(duck: IrMixinDuck, kMixinInterface: KMixinFirInterface?) = poetesse {
        java.file(duck.className) {
            interface_(fileName) { _ ->
                generatedMarker("Duck interface for binding Mixin shadows and forwarding extensions")
                suppressAllWarnings()
                public()
                kMixinInterface?.let { superinterface(it.className) }
                duck.shadows.forEach { shadow ->
                    shadow.kinds.forEach { kind ->
                        val prefixedMethod = method(kind.name) {
                            public()
                            abstract()
                            returnsIfNeeded(kind.returnType)
                            kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                        }
                        if (kMixinInterface != null) {
                            method(kind.sourceJvmName) {
                                annotation<Override>()
                                public()
                                default()
                                val returner = returnsIfNeeded(kind.returnType)
                                kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                                body {
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
                            val returner = returnsIfNeeded(kind.returnType)
                            kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                            if (kMixinInterface != null) {
                                body {
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
    private fun generateExtensions(duck: IrMixinDuck, kMixin: KMixinFir) = poetesse {
        val receiver = kotlin.code { "(this as ${T(duck.className)})" }
        kotlin.file(kMixin.className.withSuffix("_Extensions")) {
            generatedMarker(
                "Kotlin sugar providing zero-boilerplate access to the forwarded extensions in duck interface"
            )
            suppressAllWarnings()
            duck.extensions.forEach { extension ->
                when (extension) {
                    is IrMixinDuck.Extension.Property -> property(extension.declaredName, extension.type.inKotlin) {
                        public()
                        inline()
                        extensionReceiver(extension.receiverType.inKotlin)
                        getter {
                            expression {
                                val callable = code { N(extension.getter.name) }
                                "${L(receiver)}.${L(callable)}()".maybeReturnCastFromJavaTo(extension.type)
                            }
                        }
                        extension.setter?.let { setter ->
                            setter(setter.parameter.name) { newValue ->
                                body {
                                    val callable = code { N(setter.name) }
                                    val arguments = code { N(newValue).maybeCastToJava(extension.type) }
                                    line { "${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }

                    is IrMixinDuck.Extension.Function -> function(extension.declaredName) {
                        public()
                        inline()
                        extension.typeVariables.forEach { +it }
                        extensionReceiver(extension.receiverType.inKotlin)
                        extension.parameters.forEach {
                            parameter(it.name, it.type.inKotlin) {
                                if (it.type.isFunctionType) noinline()
                            }
                        }
                        val returner = returnsIfNeeded(extension.returnType)
                        body {
                            val callable = code { N(extension.name) }
                            val arguments = code {
                                extension.parameters.joinToString { N(it.name).maybeCastToJava(it.type) }
                            }
                            line {
                                "$returner${L(receiver)}.${L(callable)}(${L(arguments)})"
                                    .maybeReturnCastFromJavaTo(extension.returnType)
                            }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(duck.originatingFile))

    private fun generateKMixinImpl(kMixinImpl: IrKMixinImpl, kMixin: KMixinFirClass) = poetesse {
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
                                    parameter(parameter.name, parameter.type.inKotlin)
                                }

                                is IrKMixinImpl.ConstructorParameter.Duck -> {
                                    parameter("duck", parameter.className).property { private() }
                                }
                            }
                        }
                    }
                }
                val superclassName = if (kMixinImpl.typeVariables.isNotEmpty()) {
                    kMixin.className.generic(kMixinImpl.typeVariables)
                } else kMixin.className
                superclass(superclassName) {
                    kMixin.constructorParameters.forEach { parameter ->
                        argument {
                            when (parameter) {
                                is KMixinFirClass.ConstructorParameter.Origin -> N(parameter.name)
                            }
                        }
                    }
                }
                kMixin.mixin.duck?.shadows?.forEach { shadow ->
                    when (shadow) {
                        is IrMixinDuck.Property -> property(shadow.declaredName, shadow.type.inKotlin) {
                            public()
                            override()
                            getter {
                                expression {
                                    val receiver = code { N("duck") }
                                    val callable = code { N(shadow.getter.name) }
                                    "${L(receiver)}.${L(callable)}()".maybeReturnCastFromJavaTo(shadow.type)
                                }
                            }
                            shadow.setter?.let { setter ->
                                setter(setter.parameter.name) { newValue ->
                                    body {
                                        val receiver = code { N("duck") }
                                        val callable = code { N(setter.name) }
                                        val arguments = code { N(newValue).maybeCastToJava(shadow.type) }
                                        line { "${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                    }
                                }
                            }
                        }

                        is IrMixinDuck.Function -> function(shadow.declaredName) {
                            public()
                            override()
                            shadow.typeVariables.forEach { +it }
                            shadow.parameters.forEach { parameter(it.name, it.type.inKotlin) }
                            val returner = returnsIfNeeded(shadow.returnType)
                            body {
                                val receiver = code { N("duck") }
                                val callable = code { N(shadow.name) }
                                val arguments = code {
                                    shadow.parameters.joinToString { N(it.name).maybeCastToJava(it.type) }
                                }
                                line {
                                    "$returner${L(receiver)}.${L(callable)}(${L(arguments)})"
                                        .maybeReturnCastFromJavaTo(shadow.returnType)
                                }
                            }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(kMixinImpl.originatingFile))

    private fun generateMixinClass(mixin: IrMixin, kMixin: KMixinFirClass) = poetesse {
        val extensions = mixin.duck?.extensions.orEmpty()
        val memberInjections = mixin.injections.filterIsInstance<IrMixin.MemberInjection>()
        val delegateInitializer = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
            java.code { delegateInitializer(kMixin) }
        } else null
        java.file(mixin.className) {
            class_(fileName) { _ ->
                generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                suppressAllWarnings()
                annotations(mixin.annotations)
                public()
                abstract()
                mixin.duck?.let { superinterface(it.className) }
                val extensions = mixin.duck?.extensions.orEmpty()
                val memberInjections = mixin.injections.filterIsInstance<IrMixin.MemberInjection>()
                val delegate = delegateInitializer?.let { delegateMember(kMixin, it) }
                mixin.duck?.shadows?.forEach { shadow ->
                    when (shadow) {
                        is IrMixinDuck.Shadow.Property -> {
                            val shadowField = field(shadow.mappingName, shadow.type.inJava) {
                                annotations(shadow.annotations)
                                shadow.modifiers.forEach { +it }
                            }
                            shadow.kinds.forEach { kind ->
                                method(kind.name) {
                                    annotation<Override>()
                                    public()
                                    val returner = returnsIfNeeded(kind.returnType)
                                    kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                                    body {
                                        when (kind) {
                                            is IrMixinDuck.Property.Getter -> {
                                                line { "$returner${N(shadowField)}" }
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
                                annotations(shadow.annotations)
                                shadow.modifiers.forEach { +it }
                                returnsIfNeeded(shadow.returnType)
                                shadow.parameters.forEach { parameter(it.name, it.type.inJava) }
                                if (JPModifier.STATIC in shadow.modifiers) {
                                    body { line { "throw new ${T<AssertionError>()}(${S("Stub!")})" } }
                                }
                            }
                            method(shadow.name) {
                                annotation<Override>()
                                public()
                                val returner = returnsIfNeeded(shadow.returnType)
                                shadow.parameters.forEach { parameter(it.name, it.type.inJava) }
                                body {
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
                                val returner = returnsIfNeeded(kind.returnType)
                                kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                                body {
                                    val callable = code { N(kind.sourceJvmName) }
                                    val arguments = code { kind.parameters.joinToString { N(it.name) } }
                                    line { "$returner${L(delegate)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                    memberInjections.forEach { mixinInjection(it, delegate) }
                }
                mixin.injections.filterIsInstance<IrMixin.StaticInjection>().forEach { staticInjection ->
                    mixinInjection(staticInjection) {
                        "${T(kMixin.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(mixin.originatingFile))

    private fun JavaCodeScope.delegateInitializer(kMixin: KMixinFirClass): String {
        val (className, arguments) = if (kMixin.impl != null) {
            kMixin.impl.className to code {
                kMixin.impl.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is IrKMixinImpl.ConstructorParameter.Instance -> "this".unsafeCastTo(parameter.type)
                        is IrKMixinImpl.ConstructorParameter.Duck -> "this".castTo(parameter.className)
                    }
                }
            }
        } else {
            kMixin.className to code {
                kMixin.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is KMixinFirClass.ConstructorParameter.Origin -> "this".unsafeCastTo(parameter.type)
                    }
                }
            }
        }
        return "new ${T(className)}(${L(arguments)})"
    }

    private fun JavaTypeScope.delegateMember(
        kMixin: KMixinFirClass,
        initializer: JavaCodeRef,
    ): JavaCodeScope.() -> String {
        val isEager = kMixin.initStrategy == InitStrategy.Eager
        val isSynchronized = kMixin.initStrategy == InitStrategy.Synchronized
        val isThreadSafe = kMixin.initStrategy == InitStrategy.Volatile || isSynchronized
        val delegateField = field($$"kotlin$delegate", kMixin.className.nullable(!isEager)) {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
            if (isEager) {
                final()
                initializer(initializer)
            } else if (isThreadSafe) {
                volatile()
            }
        }
        if (isEager) return { N(delegateField) }
        val delegateLockField = if (isSynchronized) {
            field<Any>($$"kotlin$delegateLock") {
                private()
                final()
                annotation<Annotation>(xClass(options.uniqueAnnotation))
                initializer { "new ${T<Any>()}()" }
            }
        } else null
        val getDelegate = method($$"kotlin$getDelegate") {
            private()
            annotation<Annotation>(xClass(options.uniqueAnnotation))
            body {
                if (isThreadSafe) {
                    val localType = kMixin.className.nullable(true)
                    val local = var_("local", localType) { "this.${N(delegateField)}" }
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
            returns(kMixin.className)
        }
        return { "${N(getDelegate)}()" }
    }

    private fun generateMixinInterface(mixin: IrMixin, kMixinInterface: KMixinFirInterface) = poetesse {
        java.file(mixin.className) {
            interface_(fileName) { _ ->
                generatedMarker("Runtime entrypoint of the Mixin engine delegating logic to the KMixin")
                suppressAllWarnings()
                annotations(mixin.annotations)
                public()
                superinterface(mixin.duck?.className ?: kMixinInterface.className)
                mixin.duck?.shadows?.filterIsInstance<IrMixinDuck.Shadow.Function>()?.forEach { shadow ->
                    val shadowMethod = method("shadow$${shadow.mappingName}") {
                        annotations(shadow.annotations)
                        shadow.modifiers.forEach { +it }
                        returnsIfNeeded(shadow.returnType)
                        shadow.parameters.forEach { parameter(it.name, it.type.inJava) }
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
                        val returner = returnsIfNeeded(shadow.returnType)
                        shadow.parameters.forEach { parameter(it.name, it.type.inJava) }
                        body {
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

    private fun JavaTypeScope.mixinInjection(injection: IrMixin.Injection, delegate: JavaCodeScope.() -> String) {
        method(injection.name) {
            annotations(injection.annotations)
            private()
            if (injection is IrMixin.StaticInjection) static()
            val returner = returnsIfNeeded(injection.returnType)
            injection.parameters.forEach { parameter ->
                parameter(parameter.name, parameter.type.inJava) { annotations(parameter.annotations) }
            }
            body {
                val callable = code { N(injection.sourceJvmName) }
                val arguments = code {
                    buildList {
                        if (injection is IrMixin.MemberInjection && injection.extensionReceiverType != null) {
                            add("this".unsafeCastTo(injection.extensionReceiverType))
                        }
                        addAll(injection.parameters.map { N(it.name) })
                    }.joinToString()
                }
                line { "$returner${L(delegate)}.${L(callable)}(${L(arguments)})" }
            }
        }
    }

    private fun JavaAnnotationTrait.annotations(annotations: List<IrAnnotation>) {
        annotations.forEach { +annotation(it) }
    }

    private fun JavaAnnotationFactory.annotation(annotation: IrAnnotation): JavaAnnotationRef =
        annotation<Annotation>(annotation.className) {
            annotation.arguments.forEach { argument ->
                member(argument.name) {
                    when (argument) {
                        is IrAnnotation.ScalarArgument -> annotationArgumentValue(argument.value)
                        is IrAnnotation.ArrayArgument -> {
                            argument.elements.joinToString(prefix = "{", postfix = "}") { annotationArgumentValue(it) }
                        }
                    }
                }
            }
        }

    private fun JavaCodeScope.annotationArgumentValue(value: IrAnnotation.Argument.Value) = when (value) {
        is IrAnnotation.Argument.BooleanValue -> L(value.boolean)
        is IrAnnotation.Argument.ByteValue -> L(value.byte)
        is IrAnnotation.Argument.ShortValue -> L(value.short)
        is IrAnnotation.Argument.IntValue -> L(value.int)
        is IrAnnotation.Argument.LongValue -> L(value.long)
        is IrAnnotation.Argument.CharValue -> L(value.char)
        is IrAnnotation.Argument.FloatValue -> L(value.float)
        is IrAnnotation.Argument.DoubleValue -> L(value.double)
        is IrAnnotation.Argument.StringValue -> S(value.string)
        is IrAnnotation.Argument.EnumValue -> "${T(value.className)}.${N(value.name)}"
        is IrAnnotation.Argument.ClassValue -> "${T(value.className)}.class"
        is IrAnnotation.Argument.AnnotationValue -> L(poetesse.java.annotation(value.annotation))
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

context(scope: JavaCodeScope)
private fun String.castTo(targetType: XTypeName): String = with(scope) {
    "(${T(targetType)}) ${this@castTo}"
}

context(scope: JavaCodeScope)
private fun String.unsafeCastTo(targetType: IrType): String = with(scope) {
    "(${T(targetType.inJava)}) (${T<Any>()}) ${this@unsafeCastTo}"
}

context(scope: KotlinCodeScope)
private fun String.maybeCastFromJavaTo(targetType: IrType): String = with(scope) {
    targetType.castContext.toKotlin?.let { "${this@maybeCastFromJavaTo} as ${T(it)}" }
        ?: this@maybeCastFromJavaTo
}

context(scope: KotlinCodeScope)
private fun String.maybeCastToJava(targetType: IrType): String = with(scope) {
    targetType.castContext.toJava?.let { "${this@maybeCastToJava} as ${T(it)}" }
        ?: this@maybeCastToJava
}

context(scope: KotlinCodeScope)
private fun String.maybeReturnCastFromJavaTo(targetType: IrType?): String =
    if (targetType != null && targetType.isReturnable) maybeCastFromJavaTo(targetType)
    else this

private fun JavaMethodScope.returnsIfNeeded(type: IrType?): String =
    if (type != null && type.isReturnable) {
        returns(type.inJava)
        "return "
    } else ""

private fun KotlinFunctionScope.returnsIfNeeded(type: IrType?): String =
    if (type != null && type.isReturnable) {
        returns(type.inKotlin)
        "return "
    } else ""

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
