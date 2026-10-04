package io.github.diskria.lapis.ksp.phases.lowering

import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.*
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.lapis.ksp.utils.VarianceType
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class Lowering(
    private val models: List<KMixinModel>,
    private val options: KspOptions,
    private val poetesse: Poetesse,
) {
    private val mixinSourcePackageLCP by lazy {
        if (options.disableLCP) null
        else {
            models.map { it.type.packageName }.reduceOrNull { current, next ->
                val currentSegments = current.split('.')
                val nextSegments = next.split('.')
                currentSegments
                    .zip(nextSegments)
                    .takeWhile { (current, next) -> current == next }
                    .joinToString(".") { it.first }
            }
        }
    }

    fun lower() = models.map { it.lower() }

    private fun KMixinModel.lower(): KMixinFir {
        val className = type.toXClassName()
        val typeVariables = typeParameters.lower()
        val mixin = deriveMixin(className)
        return when (classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower() }
                KMixinFirClass(
                    className = className,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) {
                        deriveImpl(className, typeVariables, constructorParameters, mixin.duck)
                    } else null,
                    constructorParameters = constructorParameters,
                    initStrategy = initStrategy,
                )
            }

            KMixinModel.Interface -> KMixinFirInterface(
                className = className,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel.deriveImpl(
        originClassName: XClassName,
        typeVariables: List<XTypeVariableName>,
        constructorParameters: List<KMixinFirClass.ConstructorParameter>,
        duck: IrMixinDuck?,
    ) = IrKMixinImpl(
        originatingFile = containingFile,
        className = originClassName.withSuffix("_Impl"),
        typeVariables = typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? KMixinFirClass.ConstructorParameter.Origin }?.let {
                add(IrKMixinImpl.ConstructorParameter.Instance(it.name, it.type))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(IrKMixinImpl.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel.deriveMixin(originClassName: XClassName) = IrMixin(
        originatingFile = containingFile,
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
            val targetClassValue = IrAnnotation.Argument.ClassValue(targetType.toXClassName())
            val valueArgument = IrAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel.deriveMixinDuck(originClassName: XClassName): IrMixinDuck? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface) }
        val extensions = extensionSources.map { it.lower(targetType, typeParameters) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                originatingFile = containingFile,
                className = originClassName.withSuffix("_Duck"),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Class.ConstructorParameter.lower() = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> KMixinFirClass.ConstructorParameter.Origin(
            name = name,
            type = type.lower().irType,
        )
    }

    private fun KMixinModel.Extension.lower(
        targetType: ClassTypeModel,
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = when (this) {
        is KMixinModel.Extension.Property -> {
            val allLoweredTypeResults = mutableSetOf<LoweredTypeResult>()
            val loweredContextParameters = contextParameters.map { contextParameter ->
                val loweredTypeResult = contextParameter.type.lower().also { allLoweredTypeResults += it }
                IrFunctionParameter(name = contextParameter.name, type = loweredTypeResult.irType)
            }
            val loweredTypeResult = type.lower().also { allLoweredTypeResults += it }
            val localTypeParameters = typeParameters
            val usedEnclosingTypeParameterNames = buildSet {
                allLoweredTypeResults.forEach { addAll(it.usedTypeParameterNames) }
                retainAll(enclosingTypeParameters.map { it.name }.toSet())
                removeAll(localTypeParameters.map { it.name }.toSet())
            }
            val requiredEnclosingTypeParameters = mutableListOf<TypeParameterModel>()
            val enclosingTypeArguments = enclosingTypeParameters.map { typeParameter ->
                if (typeParameter.name in usedEnclosingTypeParameterNames) {
                    requiredEnclosingTypeParameters.add(typeParameter)
                    ClassTypeModel.GenericTypeArgument(TypeArgumentModel(typeParameter.name))
                } else {
                    ClassTypeModel.StarProjectionArgument
                }
            }
            val receiverTypeModel = if (requiredEnclosingTypeParameters.isNotEmpty()) {
                ClassTypeModel(
                    packageName = targetType.packageName,
                    qualifiedName = targetType.qualifiedName,
                    arguments = enclosingTypeArguments,
                    canonicalType = targetType.canonicalType,
                    isNullable = targetType.isNullable,
                )
            } else {
                targetType
            }
            val requiredTypeParameters = requiredEnclosingTypeParameters + localTypeParameters
            IrMixinDuck.Extension.Property(
                type = loweredTypeResult.irType,
                declaredName = declaredName,
                declaredGetterJvmName = getterJvmName,
                declaredSetterJvmName = setterJvmName,
                getterName = getterJvmName.withUniqueModPrefix(),
                setterName = setterJvmName?.withUniqueModPrefix(),
                receiverType = receiverTypeModel.lower().irType,
                contextParameters = loweredContextParameters,
                typeVariables = requiredTypeParameters.lower(),
            )
        }

        is KMixinModel.Extension.Function -> {
            val allLoweredTypeResults = mutableSetOf<LoweredTypeResult>()
            val loweredContextParameters = contextParameters.map { contextParameter ->
                val loweredTypeResult = contextParameter.type.lower().also { allLoweredTypeResults += it }
                IrFunctionParameter(name = contextParameter.name, type = loweredTypeResult.irType)
            }
            val loweredParameters = parameters.map { parameter ->
                val loweredTypeResult = parameter.type.lower().also { allLoweredTypeResults += it }
                IrFunctionParameter(name = parameter.name, type = loweredTypeResult.irType)
            }
            val loweredReturnTypeResult = returnType
                ?.lower(isSuspendFunctionReturnType = isSuspend)
                ?.also { allLoweredTypeResults += it }
            val localTypeParameters = typeParameters
            val usedEnclosingTypeParameterNames = buildSet {
                allLoweredTypeResults.forEach { addAll(it.usedTypeParameterNames) }
                retainAll(enclosingTypeParameters.map { it.name }.toSet())
                removeAll(localTypeParameters.map { it.name }.toSet())
            }
            val requiredEnclosingTypeParameters = mutableListOf<TypeParameterModel>()
            val enclosingTypeArguments = enclosingTypeParameters.map { typeParameter ->
                if (typeParameter.name in usedEnclosingTypeParameterNames) {
                    requiredEnclosingTypeParameters.add(typeParameter)
                    ClassTypeModel.GenericTypeArgument(TypeArgumentModel(typeParameter.name))
                } else {
                    ClassTypeModel.StarProjectionArgument
                }
            }
            val receiverTypeModel = if (requiredEnclosingTypeParameters.isNotEmpty()) {
                ClassTypeModel(
                    packageName = targetType.packageName,
                    qualifiedName = targetType.qualifiedName,
                    arguments = enclosingTypeArguments,
                    canonicalType = targetType.canonicalType,
                    isNullable = targetType.isNullable,
                )
            } else {
                targetType
            }
            val requiredTypeParameters = requiredEnclosingTypeParameters + localTypeParameters

            IrMixinDuck.Extension.Function(
                declaredName = declaredName,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = loweredParameters,
                contextParameters = loweredContextParameters,
                returnType = loweredReturnTypeResult?.irType,
                receiverType = receiverTypeModel.lower().irType,
                typeVariables = requiredTypeParameters.lower(),
                javaSyntheticParameters = if (isSuspend) {
                    listOf(
                        IrFunctionParameter(
                            name = "continuation",
                            type = IrType(
                                kotlin = IrKotlinType(
                                    poetesse.xClass("kotlin.coroutines.Continuation"),
                                ),
                                java = IrJavaType(
                                    poetesse.xClass("kotlin.coroutines.Continuation"),
                                ),
                            )
                        )
                    )
                } else {
                    emptyList()
                },
            )
        }
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean) = when (this) {
        is KMixinModel.Shadow.Property -> IrMixinDuck.Shadow.Property(
            type = type.lower().irType,
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
                }.map { IrAnnotation(poetesse.xClass(it)) }
            },
        )

        is KMixinModel.Shadow.Function -> IrMixinDuck.Shadow.Function(
            declaredName = declaredName,
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            parameters = parameters.map {
                IrFunctionParameter(
                    name = it.name,
                    type = it.type.lower().irType,
                )
            },
            returnType = returnType?.lower()?.irType,
            mappingName = mappingName,
            modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = false),
            annotations = if (mixinAnnotations.isNotEmpty()) {
                mixinAnnotations.map { it.lower() }
            } else {
                listOf(IrAnnotation(poetesse.xClass(options.shadowAnnotation)))
            },
            typeVariables = typeParameters.lower(),
        )
    }

    private fun KMixinModel.Injection.lowerAsMember() = IrMixin.MemberInjection(
        sourceJvmName = jvmName,
        name = jvmName.withUniqueModPrefix(),
        annotations = mixinAnnotations.map { it.lower() },
        parameters = parameters.map { it.lower() },
        contextParameters = contextParameters.map { it.lower() },
        returnType = returnType?.lower()?.irType,
        extensionReceiverType = extensionReceiverType?.lower()?.irType,
    )

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject) = IrMixin.StaticInjection(
        sourceJvmName = jvmName,
        name = jvmName.withUniqueModPrefix(),
        annotations = mixinAnnotations.map { it.lower() },
        parameters = parameters.map { it.lower() },
        contextParameters = contextParameters.map { it.lower() },
        returnType = returnType?.lower()?.irType,
        kMixinCompanionObjectName = companion.name,
    )

    private fun KMixinModel.Injection.Parameter.lower() = IrMixin.Injection.Parameter(
        name = name,
        type = type.lower().irType,
        annotations = mixinAnnotations.map { it.lower() },
    )

    private fun MixinAnnotationModel.lower() = IrAnnotation(
        className = type.toXClassName(),
        arguments = arguments.map { argument ->
            when (argument) {
                is MixinAnnotationModel.ScalarArgument -> IrAnnotation.ScalarArgument(
                    name = argument.name,
                    value = argument.value.lower(),
                )

                is MixinAnnotationModel.ArrayArgument -> IrAnnotation.ArrayArgument(
                    name = argument.name,
                    elements = argument.elements.map { it.lower() },
                )
            }
        },
    )

    private fun MixinAnnotationModel.Argument.Value.lower(): IrAnnotation.Argument.Value = when (this) {
        is MixinAnnotationModel.Argument.BooleanValue -> IrAnnotation.Argument.BooleanValue(boolean)
        is MixinAnnotationModel.Argument.ByteValue -> IrAnnotation.Argument.ByteValue(byte)
        is MixinAnnotationModel.Argument.ShortValue -> IrAnnotation.Argument.ShortValue(short)
        is MixinAnnotationModel.Argument.IntValue -> IrAnnotation.Argument.IntValue(int)
        is MixinAnnotationModel.Argument.LongValue -> IrAnnotation.Argument.LongValue(long)
        is MixinAnnotationModel.Argument.CharValue -> IrAnnotation.Argument.CharValue(char)
        is MixinAnnotationModel.Argument.FloatValue -> IrAnnotation.Argument.FloatValue(float)
        is MixinAnnotationModel.Argument.DoubleValue -> IrAnnotation.Argument.DoubleValue(double)
        is MixinAnnotationModel.Argument.StringValue -> IrAnnotation.Argument.StringValue(string)
        is MixinAnnotationModel.Argument.ClassValue -> IrAnnotation.Argument.ClassValue(type.toXClassName())
        is MixinAnnotationModel.Argument.EnumValue -> IrAnnotation.Argument.EnumValue(type.toXClassName(), name)
        is MixinAnnotationModel.Argument.AnnotationValue -> IrAnnotation.Argument.AnnotationValue(annotation.lower())
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
                bounds = typeParameter.bounds.map { it.lower().irType.kotlin.type },
            )
        }

    private class XTypeResult(
        val inKotlin: XTypeName,
        val inJava: XTypeName,
        val toKotlin: XTypeName? = null,
        val toJava: XTypeName? = null,
        val isErased: Boolean = false,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private class LoweredTypeResult(
        val irType: IrType,
        val usedTypeParameterNames: Set<String>,
    )

    private fun TypeModel.lower(isSuspendFunctionReturnType: Boolean = false): LoweredTypeResult {
        val result = toXTypeResult(isSuspendFunctionReturnType)
        val isUnit = result.inJava is XVoidTypeName && !result.inJava.isBoxed
        val irType = IrType(
            kotlin = IrKotlinType(
                type = result.inKotlin,
                javaCastType = result.toJava,
                isReturnable = !isUnit,
                isFunctionalType = this is ClassTypeModel && canonicalOrSelf.functionalType != null,
            ),
            java = IrJavaType(
                type = if (isSuspendFunctionReturnType) poetesse.xType<Any>() else result.inJava,
                kotlinCastType = result.toKotlin,
                isReturnable = isSuspendFunctionReturnType || !isUnit,
            ),
        )
        return LoweredTypeResult(irType, result.usedTypeParameterNames)
    }

    private fun TypeModel.toXTypeResult(isSuspendFunctionReturnType: Boolean = false): XTypeResult = when (this) {
        is ClassTypeModel -> toXTypeResult(isSuspendFunctionReturnType)
        is TypeArgumentModel -> toXTypeResult()
    }

    private fun TypeArgumentModel.toXTypeResult(): XTypeResult {
        val canonicalResult = canonicalType.toXTypeResult()
        val inKotlin = poetesse.xTypeVariable(name = name, nullable = isNullable)
        return XTypeResult(
            inKotlin = inKotlin,
            inJava = canonicalResult.inJava,
            toKotlin = inKotlin,
            isErased = true,
            usedTypeParameterNames = setOf(name),
        )
    }

    private fun ClassTypeModel.toXTypeResult(isSuspendFunctionReturnType: Boolean = false): XTypeResult {
        if (functionalType != null) {
            return functionalType.toXTypeResult(isNullable = isNullable)
        }
        val argResults = arguments.map { it.toXTypeResult() }
        val inKotlin = poetesse.xType(
            packageName = packageName,
            simpleNames = qualifiedName.removePrefix("$packageName.").split("."),
            typeArguments = argResults.map { it.inKotlin },
            nullable = isNullable,
        )
        val canonical = canonicalOrSelf
        val canonicalArgResults = arguments.map { it.toCanonicalXTypeName() }
        val canonicalXType = poetesse.xType(
            packageName = canonical.packageName,
            simpleNames = canonical.qualifiedName.removePrefix("${canonical.packageName}.").split("."),
            typeArguments = canonicalArgResults,
            nullable = isNullable,
        )
        val inJava = if (canonicalXType is XParameterizedTypeName) {
            canonicalXType.rawType
        } else {
            canonicalXType
        }
        val isNothing = inJava is XVoidTypeName && inJava.isNothing
        val isErased = canonicalXType is XParameterizedTypeName
        val toKotlinCast = if (isErased || isNothing || isSuspendFunctionReturnType) inKotlin else null
        return XTypeResult(
            inKotlin = inKotlin,
            inJava = inJava,
            toKotlin = toKotlinCast,
            isErased = isErased,
            usedTypeParameterNames = argResults.flatMapTo(mutableSetOf()) { it.usedTypeParameterNames },
        )
    }

    private fun ClassTypeModel.TypeArgument.toXTypeResult(): XTypeResult = when (this) {
        is ClassTypeModel.StarProjectionArgument -> {
            val star = poetesse.xStar()
            XTypeResult(
                inKotlin = star,
                inJava = star,
                isErased = true,
                usedTypeParameterNames = emptySet(),
            )
        }

        is ClassTypeModel.GenericTypeArgument -> {
            val typeResult = type.toXTypeResult()
            val inKotlin = when (variance) {
                VarianceType.INVARIANT -> typeResult.inKotlin
                VarianceType.COVARIANT -> typeResult.inKotlin.producer()
                VarianceType.CONTRAVARIANT -> typeResult.inKotlin.consumer()
            }
            XTypeResult(
                inKotlin = inKotlin,
                inJava = typeResult.inJava,
                toKotlin = typeResult.toKotlin,
                toJava = typeResult.toJava,
                isErased = typeResult.isErased,
                usedTypeParameterNames = typeResult.usedTypeParameterNames,
            )
        }
    }

    private fun ClassTypeModel.TypeArgument.toCanonicalXTypeName(): XTypeName = when (this) {
        is ClassTypeModel.StarProjectionArgument -> poetesse.xStar()
        is ClassTypeModel.GenericTypeArgument -> {
            val canonicalResult = type.toXTypeResult()
            when (variance) {
                VarianceType.INVARIANT -> canonicalResult.inJava
                VarianceType.COVARIANT -> canonicalResult.inJava.producer()
                VarianceType.CONTRAVARIANT -> canonicalResult.inJava.consumer()
            }
        }
    }

    private fun ClassTypeModel.FunctionalType.toXTypeResult(isNullable: Boolean): XTypeResult {
        val allResults = mutableListOf<XTypeResult>()
        val contextResults = contextTypes.map { it.toXTypeResult() }.also { allResults += it }
        val receiverResult = receiverType?.toXTypeResult()?.also { allResults += it }
        val paramResults = parameters.map { parameter ->
            parameter.type.toXTypeResult().also { allResults += it }
        }
        val returnResult = returnType.toXTypeResult().also { allResults += it }
        val xParameters = parameters.mapIndexed { index, parameter ->
            XParameter(name = parameter.name.orEmpty(), type = paramResults[index].inKotlin)
        }
        val inKotlin = returnResult.inKotlin.lambda(
            contextParameters = contextResults.map { it.inKotlin },
            receiver = receiverResult?.inKotlin,
            parameters = xParameters,
            isSuspending = isSuspending,
            nullable = isNullable,
        )
        val inJava = inKotlin.jvmRawClassName
        val kotlinNothing = poetesse.xType(packageName = "kotlin", simpleNames = listOf("Nothing"))
        val erasedParameters = parameters
            .map { XParameter(name = it.name.orEmpty(), type = kotlinNothing) }
            .toMutableList()
        if (isSuspending) {
            erasedParameters.add(XParameter(type = kotlinNothing))
        }
        val erasedKotlinType = poetesse.xType<Any?>().lambda(
            contextParameters = contextTypes.map { kotlinNothing },
            receiver = receiverType?.let { kotlinNothing },
            parameters = erasedParameters,
            isSuspending = false,
            nullable = isNullable,
        )
        return XTypeResult(
            inKotlin = inKotlin,
            inJava = inJava,
            toKotlin = inKotlin,
            toJava = if (isSuspending || inKotlin.isBigArity) erasedKotlinType else null,
            isErased = true,
            usedTypeParameterNames = allResults.flatMapTo(mutableSetOf()) { it.usedTypeParameterNames },
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
}
