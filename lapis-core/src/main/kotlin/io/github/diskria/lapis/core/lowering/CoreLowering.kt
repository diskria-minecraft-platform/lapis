package io.github.diskria.lapis.core.lowering

import io.github.diskria.lapis.core.cli.CliOptions
import io.github.diskria.lapis.core.lowering.models.*
import io.github.diskria.lapis.core.utils.JavaModifiers
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.core.validator.models.*
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class CoreLowering<O>(
    private val models: List<KMixinModel<O>>,
    private val options: CliOptions,
) {
    private val poetesse = options.poetesse

    private val mixinSourcePackageLCP by lazy {
        if (options.disableLCP) null
        else {
            models.map { it.type.packageName }.reduceOrNull { current, next ->
                val currentSegments = current.split('.')
                val nextSegments = next.split('.')
                currentSegments.zip(nextSegments)
                    .takeWhile { (current, next) -> current == next }
                    .joinToString(".") { it.first }
            }
        }
    }

    fun lower(): List<KMixinBlueprint<O>> = models.map { it.lower() }

    private fun KMixinModel<O>.lower(): KMixinBlueprint<O> {
        val className = type.toXClassName()
        val typeVariables = typeParameters.lower()
        val mixin = deriveMixin(className)
        return when (val classKind = classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower() }
                KMixinClassBlueprint(
                    className = className,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) {
                        deriveImpl(className, typeVariables, constructorParameters, mixin.duck)
                    } else null,
                    constructorParameters = constructorParameters,
                    initStrategy = initStrategy,
                )
            }

            KMixinModel.Interface -> KMixinInterfaceBlueprint(
                className = className,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel<O>.deriveImpl(
        originClassName: XClassName,
        typeVariables: List<XTypeVariableName>,
        constructorParameters: List<KMixinClassBlueprint.ConstructorParameter>,
        duck: MixinDuckBlueprint<*>?,
    ) = KMixinImplBlueprint(
        origin = origin,
        className = originClassName.withSuffix("_Impl"),
        typeVariables = typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? KMixinClassBlueprint.ConstructorParameter.Origin }?.let {
                add(KMixinImplBlueprint.ConstructorParameter.Instance(it.name, it.type))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(KMixinImplBlueprint.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel<O>.deriveMixin(originClassName: XClassName) = MixinBlueprint(
        origin = origin,
        className = resolveMixinClassName(originClassName),
        side = side,
        injections = buildList {
            addAll(injections.map { it.lowerAsMember() })
            companionObject?.let { companion -> addAll(companion.injections.map { it.lowerAsStatic(companion) }) }
        },
        duck = deriveMixinDuck(originClassName),
        annotations = if (mixinAnnotations.isNotEmpty()) {
            mixinAnnotations.map { it.lower() }
        } else {
            val targetClassValue = AnnotationBlueprint.Argument.ClassValue(targetType.toXClassName())
            val valueArgument = AnnotationBlueprint.ScalarArgument("value", targetClassValue)
            listOf(AnnotationBlueprint(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel<O>.deriveMixinDuck(originClassName: XClassName): MixinDuckBlueprint<O>? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface) }
        val extensions = extensionSources.map { it.lower(targetType, typeParameters) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            MixinDuckBlueprint(
                origin = origin,
                className = originClassName.withSuffix("_Duck"),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Class.ConstructorParameter.lower() = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> KMixinClassBlueprint.ConstructorParameter.Origin(
            name = name,
            type = type.lower().typeBlueprint,
        )
    }

    private fun KMixinModel.Extension.lower(
        targetType: ClassTypeModel,
        enclosingTypeParameters: List<KMixinModel.TargetTypeParameterModel>,
    ): MixinDuckBlueprint.Extension {
        val allUsedTypeParameterNames = mutableSetOf<String>()
        fun LoweredTypeResult.collectUsedTypeParameterNames() = allUsedTypeParameterNames.addAll(usedTypeParameterNames)
        val enclosingParameters = enclosingTypeParameters.associate { parameter ->
            val usedNames = parameter.bounds.flatMapTo(HashSet()) { it.lower().usedTypeParameterNames }
            parameter.name to LoweredTargetTypeParameter(model = parameter, usedTypeParameterNames = usedNames)
        }
        return when (this) {
            is KMixinModel.Extension.Property -> {
                val contextParameters = contextParameters.map { contextParameter ->
                    val typeResult = contextParameter.type.lower().also { it.collectUsedTypeParameterNames() }
                    FunctionParameterBlueprint(name = contextParameter.name, type = typeResult.typeBlueprint)
                }
                val typeResult = type.lower().also { it.collectUsedTypeParameterNames() }
                val (receiverType, typeVariables) = lowerTypeParameters(
                    usedNames = allUsedTypeParameterNames,
                    targetType = targetType,
                    enclosingParameters = enclosingParameters,
                )
                MixinDuckBlueprint.Extension.Property(
                    type = typeResult.typeBlueprint,
                    declaredName = declaredName,
                    declaredGetterJvmName = getterJvmName,
                    declaredSetterJvmName = setterJvmName,
                    getterName = getterJvmName.withUniqueModPrefix(),
                    setterName = setterJvmName?.withUniqueModPrefix(),
                    receiverType = receiverType,
                    contextParameters = contextParameters,
                    typeVariables = typeVariables,
                )
            }

            is KMixinModel.Extension.Function -> {
                val contextParameters = contextParameters.map { contextParameter ->
                    val typeResult = contextParameter.type.lower().also { it.collectUsedTypeParameterNames() }
                    FunctionParameterBlueprint(name = contextParameter.name, type = typeResult.typeBlueprint)
                }
                val parameters = parameters.map { parameter ->
                    val typeResult = parameter.type.lower().also { it.collectUsedTypeParameterNames() }
                    FunctionParameterBlueprint(name = parameter.name, type = typeResult.typeBlueprint)
                }
                val returnTypeResult = returnType?.lower()?.also { it.collectUsedTypeParameterNames() }
                val (receiverType, typeVariables) = lowerTypeParameters(
                    usedNames = allUsedTypeParameterNames,
                    targetType = targetType,
                    enclosingParameters = enclosingParameters,
                )
                MixinDuckBlueprint.Extension.Function(
                    declaredName = declaredName,
                    sourceJvmName = jvmName,
                    name = jvmName.withUniqueModPrefix(),
                    parameters = parameters,
                    contextParameters = contextParameters,
                    returnType = returnTypeResult?.typeBlueprint,
                    receiverType = receiverType,
                    typeVariables = typeVariables,
                )
            }
        }
    }

    private fun KMixinModel.Extension.lowerTypeParameters(
        usedNames: Set<String>,
        targetType: ClassTypeModel,
        enclosingParameters: Map<String, LoweredTargetTypeParameter>,
    ): LoweredExtensionTypeParametersResult {
        val localParameters = typeParameters
        val localNames = localParameters.mapTo(HashSet(localParameters.size)) { it.name }
        val transitiveUsedNames = HashSet(usedNames)
        val transitiveQueue = ArrayDeque(usedNames)
        while (transitiveQueue.isNotEmpty()) {
            val usedName = transitiveQueue.removeFirst()
            if (usedName in localNames) continue
            enclosingParameters[usedName]?.usedTypeParameterNames?.forEach { name ->
                if (transitiveUsedNames.add(name)) {
                    transitiveQueue.addLast(name)
                }
            }
        }
        val usedEnclosingParameters = enclosingParameters
            .filterKeys { it !in localNames && it in transitiveUsedNames }
            .map { it.value.model }
        val receiverTypeModel = if (usedEnclosingParameters.isNotEmpty()) {
            val substitutions = usedEnclosingParameters.associateBy { it.targetIndex }
            ClassTypeModel(
                packageName = targetType.packageName,
                qualifiedName = targetType.qualifiedName,
                typeArguments = List(targetType.typeArguments.size) { index ->
                    substitutions[index]
                        ?.let { ClassTypeModel.TypeProjectionArgument(TypeParameterTypeModel(it.name)) }
                        ?: ClassTypeModel.StarProjectionArgument
                },
                actualType = targetType.actualType,
                isNullable = targetType.isNullable,
            )
        } else {
            targetType
        }
        return LoweredExtensionTypeParametersResult(
            receiverType = receiverTypeModel.lower().typeBlueprint,
            typeVariables = (usedEnclosingParameters + localParameters).lower(),
        )
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean) = when (this) {
        is KMixinModel.Shadow.Property -> MixinDuckBlueprint.Shadow.Property(
            type = type.lower().typeBlueprint,
            declaredName = declaredName,
            declaredGetterJvmName = getterJvmName,
            declaredSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            mappingName = mappingName,
            modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = true),
            annotations = if (mixinAnnotations.isNotEmpty()) {
                mixinAnnotations.map { it.lower() }
            } else {
                buildList {
                    if (FINAL in modifiers) {
                        if (setterJvmName != null) add(options.mutableAnnotation)
                        add(options.finalAnnotation)
                    }
                    add(options.shadowAnnotation)
                }.map { AnnotationBlueprint(poetesse.xClass(it)) }
            },
        )

        is KMixinModel.Shadow.Function -> MixinDuckBlueprint.Shadow.Function(
            declaredName = declaredName,
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            parameters = parameters.map { parameter ->
                MixinDuckBlueprint.Shadow.Function.Parameter(
                    name = parameter.name,
                    mappingName = parameter.mappingName,
                    type = parameter.type.lower().typeBlueprint,
                )
            },
            returnType = returnType?.lower()?.typeBlueprint,
            mappingName = mappingName,
            modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = false),
            annotations = if (mixinAnnotations.isNotEmpty()) {
                mixinAnnotations.map { it.lower() }
            } else {
                listOf(AnnotationBlueprint(poetesse.xClass(options.shadowAnnotation)))
            },
            typeVariables = typeParameters.lower(),
        )
    }

    private fun KMixinModel.Injection.lowerAsMember() = MixinBlueprint.MemberInjection(
        sourceJvmName = jvmName,
        name = jvmName.withUniqueModPrefix(),
        annotations = mixinAnnotations.map { it.lower() },
        parameters = parameters.map { it.lower() },
        contextParameters = contextParameters.map { it.lower() },
        returnType = returnType?.lower()?.typeBlueprint,
        extensionReceiverType = extensionReceiverType?.lower()?.typeBlueprint,
    )

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject) = MixinBlueprint.StaticInjection(
        sourceJvmName = jvmName,
        name = jvmName.withUniqueModPrefix(),
        annotations = mixinAnnotations.map { it.lower() },
        parameters = parameters.map { it.lower() },
        contextParameters = contextParameters.map { it.lower() },
        returnType = returnType?.lower()?.typeBlueprint,
        kMixinCompanionObjectName = companion.name,
    )

    private fun KMixinModel.Injection.Parameter.lower() = MixinBlueprint.Injection.Parameter(
        name = name,
        type = type.lower().typeBlueprint,
        annotations = mixinAnnotations.map { it.lower() },
    )

    private fun MixinAnnotationModel.lower() = AnnotationBlueprint(
        className = type.toXClassName(),
        arguments = arguments.map { argument ->
            when (argument) {
                is MixinAnnotationModel.ScalarArgument -> AnnotationBlueprint.ScalarArgument(
                    name = argument.name,
                    value = argument.value.lower(),
                )

                is MixinAnnotationModel.ArrayArgument -> AnnotationBlueprint.ArrayArgument(
                    name = argument.name,
                    elements = argument.elements.map { it.lower() },
                )
            }
        },
    )

    private fun MixinAnnotationModel.Argument.Value.lower(): AnnotationBlueprint.Argument.Value = when (this) {
        is MixinAnnotationModel.Argument.BooleanValue -> AnnotationBlueprint.Argument.BooleanValue(boolean)
        is MixinAnnotationModel.Argument.ByteValue -> AnnotationBlueprint.Argument.ByteValue(byte)
        is MixinAnnotationModel.Argument.ShortValue -> AnnotationBlueprint.Argument.ShortValue(short)
        is MixinAnnotationModel.Argument.IntValue -> AnnotationBlueprint.Argument.IntValue(int)
        is MixinAnnotationModel.Argument.LongValue -> AnnotationBlueprint.Argument.LongValue(long)
        is MixinAnnotationModel.Argument.CharValue -> AnnotationBlueprint.Argument.CharValue(char)
        is MixinAnnotationModel.Argument.FloatValue -> AnnotationBlueprint.Argument.FloatValue(float)
        is MixinAnnotationModel.Argument.DoubleValue -> AnnotationBlueprint.Argument.DoubleValue(double)
        is MixinAnnotationModel.Argument.StringValue -> AnnotationBlueprint.Argument.StringValue(string)
        is MixinAnnotationModel.Argument.ClassValue -> AnnotationBlueprint.Argument.ClassValue(type.toXClassName())
        is MixinAnnotationModel.Argument.EnumValue -> AnnotationBlueprint.Argument.EnumValue(type.toXClassName(), name)
        is MixinAnnotationModel.Argument.AnnotationValue -> AnnotationBlueprint.Argument.AnnotationValue(annotation.lower())
    }

    private fun EnumSet<Modifier>.lowerToShadowModifiers(isInterface: Boolean, isField: Boolean): List<JPModifier> {
        val result = EnumSet.copyOf(this)
        if (isField) {
            result.remove(FINAL)
        } else if (isInterface) {
            result.remove(DEFAULT)
            if (PRIVATE !in result && STATIC !in result) {
                result.add(ABSTRACT)
            }
        } else if (STATIC !in result) {
            result.add(ABSTRACT)
            if (PRIVATE in result) {
                result.remove(PRIVATE)
                result.add(PROTECTED)
            }
            result.removeAll(JavaModifiers.ABSTRACT_ILLEGALS)
        }
        return result.toList()
    }

    private fun String.withUniqueModPrefix(): String =
        options.uniqueModPrefix + this

    private fun List<TypeParameterModel>.lower(): List<XTypeVariableName> =
        map { typeParameter ->
            poetesse.xTypeVariable(
                name = typeParameter.name,
                bounds = typeParameter.bounds.map { it.lower().typeBlueprint.inKotlin },
            )
        }

    private fun TypeModel.lower(): LoweredTypeResult = when (this) {
        is ClassTypeModel -> {
            val kotlinResult = toTypeName(forJava = false)
            val kotlinTypeName = kotlinResult.typeName
            val javaResult = toTypeName(forJava = true)
            val javaTypeName = javaResult.typeName
            val typeBlueprint = if (actualOrThis.functionalType != null) {
                val toJavaTypeName = (javaTypeName as? XClassName)
                    ?.takeIf { it.qualifiedName == XFunctionalTypeName.JVM_FUNCTION_N }
                    ?.generic(poetesse.xStar())
                TypeBlueprint(
                    inKotlin = kotlinTypeName,
                    inJava = javaTypeName,
                    castContext = TypeBlueprint.CastContext(toKotlin = kotlinTypeName, toJava = toJavaTypeName),
                    isReturnable = true,
                    isFunctionType = true,
                )
            } else {
                val isUnit = javaTypeName is XVoidTypeName && !javaTypeName.isBoxed
                val isNothing = javaTypeName is XVoidTypeName && javaTypeName.isNothing
                val toKotlinTypeName = if (javaResult.isErased || isNothing) kotlinTypeName else null
                TypeBlueprint(
                    inKotlin = kotlinTypeName,
                    inJava = javaTypeName,
                    castContext = TypeBlueprint.CastContext(toKotlin = toKotlinTypeName, toJava = null),
                    isReturnable = !isUnit,
                )
            }
            LoweredTypeResult(typeBlueprint, kotlinResult.usedTypeParameterNames)
        }

        is TypeParameterTypeModel -> {
            val kotlinResult = toTypeName(forJava = false)
            val kotlinTypeName = kotlinResult.typeName
            val javaTypeName = toTypeName(forJava = true).typeName
            val isUnit = javaTypeName is XVoidTypeName && !javaTypeName.isBoxed
            val typeBlueprint = TypeBlueprint(
                inKotlin = kotlinTypeName,
                inJava = javaTypeName,
                castContext = TypeBlueprint.CastContext(toKotlin = kotlinTypeName, toJava = null),
                isReturnable = !isUnit,
            )
            LoweredTypeResult(typeBlueprint, kotlinResult.usedTypeParameterNames)
        }
    }

    private fun TypeModel.toTypeName(forJava: Boolean): TypeNameResult = when (this) {
        is ClassTypeModel -> toTypeName(forJava)
        is TypeParameterTypeModel -> {
            if (forJava) {
                val javaResult = upperBound.toTypeName(forJava = true)
                TypeNameResult(typeName = javaResult.typeName, isErased = true)
            } else {
                TypeNameResult(
                    typeName = poetesse.xTypeVariable(name = name, nullable = isNullable),
                    usedTypeParameterNames = setOf(name),
                )
            }
        }
    }

    private fun ClassTypeModel.toTypeName(forJava: Boolean): TypeNameResult {
        val type = if (forJava) actualOrThis else this
        val typeResult = type.detectTypeName(forJava)
        if (forJava && typeResult.typeName is XParameterizedTypeName) {
            return TypeNameResult(
                typeName = typeResult.typeName.rawType,
                isErased = true,
                usedTypeParameterNames = typeResult.usedTypeParameterNames,
            )
        }
        return typeResult
    }

    private fun ClassTypeModel.TypeArgument.toTypeName(forJava: Boolean): TypeNameResult = when (this) {
        is ClassTypeModel.StarProjectionArgument -> TypeNameResult(poetesse.xStar())
        is ClassTypeModel.TypeProjectionArgument -> {
            val typeResult = type.toTypeName(forJava)
            val typeName = when (variance) {
                Variance.INVARIANT -> typeResult.typeName
                Variance.COVARIANT -> typeResult.typeName.producer()
                Variance.CONTRAVARIANT -> typeResult.typeName.consumer()
            }
            TypeNameResult(
                typeName = typeName,
                isErased = typeResult.isErased,
                usedTypeParameterNames = typeResult.usedTypeParameterNames,
            )
        }
    }

    private fun ClassTypeModel.detectTypeName(forJava: Boolean): TypeNameResult {
        if (functionalType != null) {
            return functionalType.toTypeName(forJava = forJava, isNullable = isNullable)
        }
        val argumentTypeResults = typeArguments.map { it.toTypeName(forJava) }
        return TypeNameResult(
            typeName = poetesse.xType(
                packageName = packageName,
                simpleNames = qualifiedName.removePrefix("$packageName.").split("."),
                typeArguments = argumentTypeResults.map { it.typeName },
                nullable = isNullable,
            ),
            isErased = argumentTypeResults.any { it.isErased },
            usedTypeParameterNames = if (forJava) {
                emptySet()
            } else {
                argumentTypeResults.flatMapTo(mutableSetOf()) { it.usedTypeParameterNames }
            },
        )
    }

    private fun ClassTypeModel.FunctionalType.toTypeName(forJava: Boolean, isNullable: Boolean): TypeNameResult {
        val allTypeResults = mutableListOf<TypeNameResult>()
        val contextTypeResults = contextTypes.map { it.toTypeName(forJava) }.also { allTypeResults += it }
        val receiverTypeResult = receiverType?.toTypeName(forJava)?.also { allTypeResults += it }
        val returnTypeResult = returnType.toTypeName(forJava).also { allTypeResults += it }
        val typeName = returnTypeResult.typeName.lambda(
            contextParameters = contextTypeResults.map { it.typeName },
            receiver = receiverTypeResult?.typeName,
            parameters = parameters.map { parameter ->
                val typeResult = parameter.type.toTypeName(forJava).also { allTypeResults += it }
                XParameter(name = parameter.name.orEmpty(), type = typeResult.typeName)
            },
            nullable = isNullable,
        )
        if (forJava) {
            return TypeNameResult(typeName = typeName.jvmRawClassName, isErased = true)
        }
        return TypeNameResult(
            typeName = typeName,
            usedTypeParameterNames = allTypeResults.flatMapTo(mutableSetOf()) { it.usedTypeParameterNames },
        )
    }

    private fun ClassTypeModel.toXClassName(): XClassName =
        poetesse.xClass(packageName, qualifiedName.removePrefix("$packageName.").split("."), nullable = isNullable)

    private fun resolveMixinClassName(srcClassName: XClassName): XClassName {
        val srcPackageName = srcClassName.packageName
        val outPackageName = buildString {
            append(options.mixinPackage)
            options.mixinGeneratedSubpackage?.let { append(".$it") }
            if (srcPackageName != null && mixinSourcePackageLCP != null && srcPackageName != mixinSourcePackageLCP) {
                append(".${srcPackageName.removePrefix("$mixinSourcePackageLCP.")}")
            }
        }
        return poetesse.xClass(outPackageName, srcClassName.simpleName).withSuffix("_Generated")
    }

    private data class TypeNameResult(
        val typeName: XTypeName,
        val isErased: Boolean = false,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private data class LoweredTypeResult(
        val typeBlueprint: TypeBlueprint,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private data class LoweredTargetTypeParameter(
        val model: KMixinModel.TargetTypeParameterModel,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private data class LoweredExtensionTypeParametersResult(
        val receiverType: TypeBlueprint,
        val typeVariables: List<XTypeVariableName> = emptyList(),
    )
}
