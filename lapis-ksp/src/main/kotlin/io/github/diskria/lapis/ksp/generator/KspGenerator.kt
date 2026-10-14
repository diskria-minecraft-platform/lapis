package io.github.diskria.lapis.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.InitStrategy
import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.extensions.qualifiedNameOf
import io.github.diskria.lapis.core.lowering.models.*
import io.github.diskria.lapis.ksp.generator.models.GeneratedMixinsJson
import io.github.diskria.poetesse.PoetesseFile
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.*
import io.github.diskria.poetesse.kotlin.*
import kotlinx.serialization.json.Json
import javax.annotation.processing.Generated

class KspGenerator(
    private val kMixins: List<KMixinBlueprint<KSFile>>,
    private val options: CliOptions,
    private val codeGenerator: CodeGenerator,
) {
    private val poetesse = options.poetesse

    fun generate() {
        kMixins.forEach { kMixin ->
            kMixin.mixin.duck?.let {
                generateMixinDuck(it, kMixin)
                if (it.extensions.isNotEmpty()) {
//                    generateExtensions(it, kMixin)
                }
            }
            when (kMixin) {
                is KMixinClassBlueprint -> {
                    kMixin.impl?.let { generateKMixinImpl(it, kMixin) }
                    generateMixinClass(kMixin.mixin, kMixin)
                }

                is KMixinInterfaceBlueprint -> {
                    generateMixinInterface(kMixin.mixin, kMixin)
                }
            }
        }
        generateMixinConfig(kMixins.map { it.mixin })
    }

    private fun generateMixinDuck(duck: MixinDuckBlueprint<KSFile>, kMixin: KMixinBlueprint<KSFile>) = poetesse {
        java.file(duck.className) {
            interface_(fileName) { _ ->
                generatedMarkers(
                    originClassName = kMixin.className,
                    roleDesc = "Duck interface for binding Mixin shadows and forwarding extensions",
                )
                suppressAllWarnings()
                public()
                if (kMixin is KMixinInterfaceBlueprint) {
                    superinterface(kMixin.className)
                }
                duck.shadows.forEach { shadow ->
                    shadow.kinds.forEach { kind ->
                        val prefixedMethod = method(kind.name) {
                            public()
                            abstract()
                            returnsIfNeeded(kind.returnType)
                            kind.parameters.forEach { parameter(it.name, it.type.inJava) }
                        }
                        if (kMixin is KMixinInterfaceBlueprint) {
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
                            if (kMixin is KMixinInterfaceBlueprint) default() else abstract()
                            val returner = returnsIfNeeded(kind.returnType)
                            val allParameters = extension.contextParameters + kind.parameters
                            allParameters.forEach { parameter(it.name, it.type.inJava) }
                            if (kMixin is KMixinInterfaceBlueprint) {
                                body {
                                    val receiver = code { "${T(kMixin.className)}.super" }
                                    val callable = code { N(kind.sourceJvmName) }
                                    val arguments = code { allParameters.joinToString { N(it.name) } }
                                    line { "$returner${L(receiver)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(duck.origin))

//    // TODO: Migrate to FIR plugin
//    private fun generateExtensions(duck: MixinDuckBlueprint<KSFile>, kMixin: KMixinBlueprint<KSFile>) = poetesse {
//        val receiver = kotlin.code { "(this as ${T(duck.className)})" }
//        kotlin.file(kMixin.className.withSuffix("_Extensions")) {
//            duck.extensions.forEach { extension ->
//                when (extension) {
//                    is MixinDuckBlueprint.Extension.Property -> property(extension.declaredName, extension.type.inKotlin) {
//                        extension.contextParameters.forEach { contextParameter(it.name, it.type.inKotlin) }
//                        public()
//                        inline()
//                        extension.typeVariables.forEach { +it }
//                        extensionReceiver(extension.receiverType.inKotlin)
//                        getter {
//                            expression {
//                                val callable = code { N(extension.getter.name) }
//                                val arguments = code {
//                                    extension.contextParameters.joinToString { N(it.name).maybeCastToJava(it.type) }
//                                }
//                                "${L(receiver)}.${L(callable)}(${L(arguments)})"
//                                    .maybeReturnCastFromJavaTo(extension.type)
//                            }
//                        }
//                        extension.setter?.let { setter ->
//                            setter(setter.parameter.name) { newValue ->
//                                body {
//                                    val callable = code { N(setter.name) }
//                                    val arguments = code {
//                                        val nameToType = buildList {
//                                            addAll(extension.contextParameters.map { it.name to it.type })
//                                            add(newValue to extension.type)
//                                        }
//                                        nameToType.joinToString { (name, type) -> N(name).maybeCastToJava(type) }
//                                    }
//                                    line { "${L(receiver)}.${L(callable)}(${L(arguments)})" }
//                                }
//                            }
//                        }
//                    }
//
//                    is MixinDuckBlueprint.Extension.Function -> function(extension.declaredName) {
//                        extension.contextParameters.forEach { contextParameter(it.name, it.type.inKotlin) }
//                        public()
//                        inline()
//                        extension.typeVariables.forEach { +it }
//                        extensionReceiver(extension.receiverType.inKotlin)
//                        extension.parameters.forEach {
//                            parameter(it.name, it.type.inKotlin) {
//                                if (it.type.isFunctionType) noinline()
//                            }
//                        }
//                        val returner = returnsIfNeeded(extension.returnType)
//                        body {
//                            val callable = code { N(extension.name) }
//                            val arguments = code {
//                                (extension.contextParameters + extension.parameters)
//                                    .joinToString { N(it.name).maybeCastToJava(it.type) }
//                            }
//                            line {
//                                "$returner${L(receiver)}.${L(callable)}(${L(arguments)})"
//                                    .maybeReturnCastFromJavaTo(extension.returnType)
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }.writeWith(aggregating = false, listOfNotNull(duck.origin))

    private fun generateKMixinImpl(kMixinImpl: KMixinImplBlueprint<KSFile>, kMixin: KMixinClassBlueprint<KSFile>) = poetesse {
        kotlin.file(kMixinImpl.className) {
            class_(fileName) { _ ->
                generatedMarkers(
                    originClassName = kMixin.className,
                    roleDesc = "KMixin implementation for binding Mixin shadows",
                )
                suppressAllWarnings()
                public()
                kMixinImpl.typeVariables.forEach { +it }
                if (kMixinImpl.constructorParameters.isNotEmpty()) {
                    constructor(primary = true) {
                        public()
                        kMixinImpl.constructorParameters.forEach { parameter ->
                            when (parameter) {
                                is KMixinImplBlueprint.ConstructorParameter.Instance -> {
                                    parameter(parameter.name, parameter.type.inKotlin)
                                }

                                is KMixinImplBlueprint.ConstructorParameter.Duck -> {
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
                                is KMixinClassBlueprint.ConstructorParameter.Origin -> N(parameter.name)
                            }
                        }
                    }
                }
                kMixin.mixin.duck?.shadows?.forEach { shadow ->
                    when (shadow) {
                        is MixinDuckBlueprint.Shadow.Property -> property(shadow.declaredName, shadow.type.inKotlin) {
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

                        is MixinDuckBlueprint.Shadow.Function -> function(shadow.declaredName) {
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
    }.writeWith(aggregating = false, listOfNotNull(kMixinImpl.origin))

    private fun generateMixinClass(mixin: MixinBlueprint<KSFile>, kMixin: KMixinClassBlueprint<KSFile>) = poetesse {
        val extensions = mixin.duck?.extensions.orEmpty()
        val memberInjections = mixin.injections.filterIsInstance<MixinBlueprint.MemberInjection>()
        val delegateInitializer = if (extensions.isNotEmpty() || memberInjections.isNotEmpty()) {
            java.code { delegateInitializer(kMixin) }
        } else null
        java.file(mixin.className) {
            class_(fileName) { _ ->
                generatedMarkers(
                    originClassName = kMixin.className,
                    roleDesc = "Runtime entrypoint of the Mixin engine delegating logic to the KMixin",
                )
                suppressAllWarnings()
                annotations(mixin.annotations)
                public()
                abstract()
                mixin.duck?.let { superinterface(it.className) }
                val extensions = mixin.duck?.extensions.orEmpty()
                val memberInjections = mixin.injections.filterIsInstance<MixinBlueprint.MemberInjection>()
                val delegate = delegateInitializer?.let { delegateMember(kMixin, it) }
                mixin.duck?.shadows?.forEach { shadow ->
                    when (shadow) {
                        is MixinDuckBlueprint.Shadow.Property -> {
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
                                            is MixinDuckBlueprint.Property.Getter -> {
                                                line { "$returner${N(shadowField)}" }
                                            }

                                            is MixinDuckBlueprint.Property.Setter -> {
                                                line { "${N(shadowField)} = ${kind.parameter.name}" }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is MixinDuckBlueprint.Shadow.Function -> {
                            val shadowMethod = method(shadow.mappingName) {
                                annotations(shadow.annotations)
                                shadow.modifiers.forEach { +it }
                                returnsIfNeeded(shadow.returnType)
                                shadow.parameters.forEach { parameter(it.mappingName, it.type.inJava) }
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
                                val allParameters = extension.contextParameters + kind.parameters
                                allParameters.forEach { parameter(it.name, it.type.inJava) }
                                body {
                                    val callable = code { N(kind.sourceJvmName) }
                                    val arguments = code { allParameters.joinToString { N(it.name) } }
                                    line { "$returner${L(delegate)}.${L(callable)}(${L(arguments)})" }
                                }
                            }
                        }
                    }
                    memberInjections.forEach { mixinInjection(it, delegate) }
                }
                mixin.injections.filterIsInstance<MixinBlueprint.StaticInjection>().forEach { staticInjection ->
                    mixinInjection(staticInjection) {
                        "${T(kMixin.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(mixin.origin))

    private fun JavaCodeScope.delegateInitializer(kMixin: KMixinClassBlueprint<KSFile>): String {
        val impl = kMixin.impl
        val (className, arguments) = if (impl != null) {
            impl.className to code {
                impl.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is KMixinImplBlueprint.ConstructorParameter.Instance -> "this".unsafeCastTo(parameter.type)
                        is KMixinImplBlueprint.ConstructorParameter.Duck -> "this".castTo(parameter.className)
                    }
                }
            }
        } else {
            kMixin.className to code {
                kMixin.constructorParameters.joinToString { parameter ->
                    when (parameter) {
                        is KMixinClassBlueprint.ConstructorParameter.Origin -> "this".unsafeCastTo(parameter.type)
                    }
                }
            }
        }
        return "new ${T(className)}(${L(arguments)})"
    }

    private fun JavaTypeScope.delegateMember(
        kMixin: KMixinClassBlueprint<KSFile>,
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

    private fun generateMixinInterface(mixin: MixinBlueprint<KSFile>, kMixin: KMixinInterfaceBlueprint<KSFile>) = poetesse {
        java.file(mixin.className) {
            interface_(fileName) { _ ->
                generatedMarkers(
                    originClassName = kMixin.className,
                    roleDesc = "Runtime entrypoint of the Mixin engine delegating logic to the KMixin",
                )
                suppressAllWarnings()
                annotations(mixin.annotations)
                public()
                superinterface(mixin.duck?.className ?: kMixin.className)
                mixin.duck?.shadows?.filterIsInstance<MixinDuckBlueprint.Shadow.Function>()?.forEach { shadow ->
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
                mixin.injections.filterIsInstance<MixinBlueprint.MemberInjection>().forEach { memberInjection ->
                    mixinInjection(memberInjection) {
                        "${T(mixin.duck?.className ?: kMixin.className)}.super"
                    }
                }
                mixin.injections.filterIsInstance<MixinBlueprint.StaticInjection>().forEach { staticInjection ->
                    mixinInjection(staticInjection) {
                        "${T(kMixin.className)}.${N(staticInjection.kMixinCompanionObjectName)}"
                    }
                }
            }
        }
    }.writeWith(aggregating = false, listOfNotNull(mixin.origin))

    private fun JavaTypeScope.mixinInjection(injection: MixinBlueprint.Injection, delegate: JavaCodeScope.() -> String) {
        method(injection.name) {
            annotations(injection.annotations)
            private()
            if (injection is MixinBlueprint.StaticInjection) static()
            val returner = returnsIfNeeded(injection.returnType)
            (injection.parameters + injection.contextParameters).forEach { parameter ->
                parameter(parameter.name, parameter.type.inJava) { annotations(parameter.annotations) }
            }
            body {
                val callable = code { N(injection.sourceJvmName) }
                val arguments = code {
                    buildList {
                        if (injection is MixinBlueprint.MemberInjection) {
                            injection.extensionReceiverType?.let { add("this".unsafeCastTo(it)) }
                        }
                        addAll((injection.contextParameters + injection.parameters).map { N(it.name) })
                    }.joinToString()
                }
                line { "$returner${L(delegate)}.${L(callable)}(${L(arguments)})" }
            }
        }
    }

    private fun JavaAnnotationTrait.annotations(annotations: List<AnnotationBlueprint>) {
        annotations.forEach { +annotation(it) }
    }

    private fun JavaAnnotationFactory.annotation(annotation: AnnotationBlueprint): JavaAnnotationRef =
        annotation<Annotation>(annotation.className) {
            annotation.arguments.forEach { argument ->
                member(argument.name) {
                    when (argument) {
                        is AnnotationBlueprint.ScalarArgument -> annotationArgumentValue(argument.value)
                        is AnnotationBlueprint.ArrayArgument -> {
                            argument.elements.joinToString(prefix = "{", postfix = "}") { annotationArgumentValue(it) }
                        }
                    }
                }
            }
        }

    private fun JavaCodeScope.annotationArgumentValue(value: AnnotationBlueprint.Argument.Value) = when (value) {
        is AnnotationBlueprint.Argument.BooleanValue -> L(value.boolean)
        is AnnotationBlueprint.Argument.ByteValue -> L(value.byte)
        is AnnotationBlueprint.Argument.ShortValue -> L(value.short)
        is AnnotationBlueprint.Argument.IntValue -> L(value.int)
        is AnnotationBlueprint.Argument.LongValue -> L(value.long)
        is AnnotationBlueprint.Argument.CharValue -> L(value.char)
        is AnnotationBlueprint.Argument.FloatValue -> L(value.float)
        is AnnotationBlueprint.Argument.DoubleValue -> L(value.double)
        is AnnotationBlueprint.Argument.StringValue -> S(value.string)
        is AnnotationBlueprint.Argument.EnumValue -> "${T(value.className)}.${N(value.name)}"
        is AnnotationBlueprint.Argument.ClassValue -> "${T(value.className)}.class"
        is AnnotationBlueprint.Argument.AnnotationValue -> L(poetesse.java.annotation(value.annotation))
    }

    private fun PoetesseFile.writeWith(aggregating: Boolean, originatingFiles: Iterable<KSFile>) {
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating, *originatingFiles.toList().toTypedArray()),
            packageName = packageName.orEmpty(),
            fileName = fileName,
            extensionName = extensionName,
        ).writer().use(::writeTo)
    }

    private fun generateMixinConfig(mixins: List<MixinBlueprint<KSFile>>) {
        generateResourceFile("generated-mixins.json", mixins.mapNotNull { it.origin }, aggregating = true) {
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
}

context(scope: JavaCodeScope)
private fun String.castTo(targetType: XTypeName): String = with(scope) {
    "(${T(targetType)}) ${this@castTo}"
}

context(scope: JavaCodeScope)
private fun String.unsafeCastTo(targetType: TypeBlueprint): String = with(scope) {
    "(${T(targetType.inJava)}) (${T<Any>()}) ${this@unsafeCastTo}"
}

context(scope: KotlinCodeScope)
private fun String.maybeCastFromJavaTo(targetType: TypeBlueprint): String = with(scope) {
    targetType.castContext.toKotlin?.let { "${this@maybeCastFromJavaTo} as ${T(it)}" }
        ?: this@maybeCastFromJavaTo
}

context(scope: KotlinCodeScope)
private fun String.maybeCastToJava(targetType: TypeBlueprint): String = with(scope) {
    targetType.castContext.toJava?.let { "${this@maybeCastToJava} as ${T(it)}" }
        ?: this@maybeCastToJava
}

context(scope: KotlinCodeScope)
private fun String.maybeReturnCastFromJavaTo(targetType: TypeBlueprint?): String =
    if (targetType != null && targetType.isReturnable) maybeCastFromJavaTo(targetType)
    else this

private fun JavaMethodScope.returnsIfNeeded(type: TypeBlueprint?): String =
    if (type != null && type.isReturnable) {
        returns(type.inJava)
        "return "
    } else ""

private fun KotlinFunctionScope.returnsIfNeeded(type: TypeBlueprint?): String =
    if (type != null && type.isReturnable) {
        returns(type.inKotlin)
        "return "
    } else ""

private fun JavaTypeScope.suppressAllWarnings() {
    annotation<SuppressWarnings> {
        member(SuppressWarnings::value, "ALL")
    }
}

private fun KotlinTypeScope.suppressAllWarnings() {
    annotation<Suppress> {
        member(Suppress::names, "warnings")
    }
}

private fun JavaTypeScope.generatedMarkers(originClassName: XClassName, roleDesc: String) {
    documentation {
        line { "AUTO-GENERATED FILE. DO NOT MODIFY." }
        line { "Generated from origin: {@link ${originClassName.qualifiedName}}" }
    }
    annotation<Generated> {
        member(Generated::value, qualifiedNameOf<KspGenerator>())
        member(Generated::comments, roleDesc)
    }
}

private fun KotlinTypeScope.generatedMarkers(originClassName: XClassName, roleDesc: String) {
    documentation {
        line { "AUTO-GENERATED FILE. DO NOT MODIFY." }
        line { "Generated from origin: [${originClassName.qualifiedName}]" }
    }
    annotation<Generated> {
        member(Generated::value, qualifiedNameOf<KspGenerator>())
        member(Generated::comments, roleDesc)
    }
}
