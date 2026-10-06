package io.github.diskria.lapis.core.lowering

import io.github.diskria.lapis.core.CoreOptions
import io.github.diskria.lapis.core.lowering.models.*
import io.github.diskria.lapis.core.utils.JavaModifiers
import io.github.diskria.lapis.core.utils.Variance
import io.github.diskria.lapis.core.validator.models.*
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class CoreLowering<O>(
    private val models: List<KMixinModel<O>>,
    private val options: CoreOptions,
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

    fun lower(): List<IrKMixin<O>> = models.map { it.lower() }

    private fun KMixinModel<O>.lower(): IrKMixin<O> {
        val className = type.toXClassName()
        val typeVariables = typeParameters.lower()
        val mixin = deriveMixin(className)
        return when (val classKind = classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower() }
                IrKMixinClass(
                    className = className,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) {
                        deriveImpl(className, typeVariables, constructorParameters, mixin.duck)
                    } else null,
                    constructorParameters = constructorParameters,
                    initStrategy = initStrategy,
                )
            }

            KMixinModel.Interface -> IrKMixinInterface(
                className = className,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel<O>.deriveImpl(
        originClassName: XClassName,
        typeVariables: List<XTypeVariableName>,
        constructorParameters: List<IrKMixinClass.ConstructorParameter>,
        duck: IrMixinDuck<*>?,
    ) = IrKMixinImpl(
        origin = origin,
        className = originClassName.withSuffix("_Impl"),
        typeVariables = typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? IrKMixinClass.ConstructorParameter.Origin }?.let {
                add(IrKMixinImpl.ConstructorParameter.Instance(it.name, it.type))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(IrKMixinImpl.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel<O>.deriveMixin(originClassName: XClassName) = IrMixin(
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
            val targetClassValue = IrAnnotation.Argument.ClassValue(targetType.toXClassName())
            val valueArgument = IrAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel<O>.deriveMixinDuck(originClassName: XClassName): IrMixinDuck<O>? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface) }
        val extensions = extensionSources.map { it.lower(targetType, typeParameters) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                origin = origin,
                className = originClassName.withSuffix("_Duck"),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Class.ConstructorParameter.lower() = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> IrKMixinClass.ConstructorParameter.Origin(
            name = name,
            type = type.lower().irType,
        )
    }

    private fun KMixinModel.Extension.lower(
        targetType: ClassTypeModel,
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = when (this) {
        is KMixinModel.Extension.Property -> {
            val allLoweredTypes = mutableSetOf<LoweredType>()
            val loweredContextParameters = contextParameters.map { contextParameter ->
                val loweredTypeResult = contextParameter.type.lower().also { allLoweredTypes += it }
                IrFunctionParameter(name = contextParameter.name, type = loweredTypeResult.irType)
            }
            val loweredTypeResult = type.lower().also { allLoweredTypes += it }
            val localTypeParameters = typeParameters
            val usedEnclosingTypeParameterNames = buildSet {
                allLoweredTypes.forEach { addAll(it.usedTypeParameterNames) }
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
                    actualType = targetType.actualType,
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
            val allLoweredTypes = mutableSetOf<LoweredType>()
            val loweredContextParameters = contextParameters.map { contextParameter ->
                val loweredTypeResult = contextParameter.type.lower().also { allLoweredTypes += it }
                IrFunctionParameter(name = contextParameter.name, type = loweredTypeResult.irType)
            }
            val loweredParameters = parameters.map { parameter ->
                val loweredTypeResult = parameter.type.lower().also { allLoweredTypes += it }
                IrFunctionParameter(name = parameter.name, type = loweredTypeResult.irType)
            }
            val loweredReturnTypeResult = returnType?.lower()?.also { allLoweredTypes += it }
            val localTypeParameters = typeParameters
            val usedEnclosingTypeParameterNames = buildSet {
                allLoweredTypes.forEach { addAll(it.usedTypeParameterNames) }
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
                    actualType = targetType.actualType,
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
                bounds = typeParameter.bounds.map { it.lower().irType.inKotlin },
            )
        }

    private class TypeNameResult(
        val typeName: XTypeName,
        val isErased: Boolean = false,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private class LoweredType(
        val irType: IrType,
        val usedTypeParameterNames: Set<String>,
    )

    private fun TypeModel.lower(): LoweredType = when (this) {
        is ClassTypeModel -> {
            val kotlinResult = toTypeName(forJava = false)
            val kotlinTypeName = kotlinResult.typeName
            val javaResult = toTypeName(forJava = true)
            val javaTypeName = javaResult.typeName
            val irType = if (actualOrSelf.functionalType != null) {
                val toJavaTypeName = (javaTypeName as? XClassName)
                    ?.takeIf { it.qualifiedName == XFunctionalTypeName.JVM_FUNCTION_N }
                    ?.generic(poetesse.xStar())
                IrType(
                    inKotlin = kotlinTypeName,
                    inJava = javaTypeName,
                    castContext = IrType.CastContext(toKotlin = kotlinTypeName, toJava = toJavaTypeName),
                    isReturnable = true,
                    isFunctionType = true,
                )
            } else {
                val isUnit = javaTypeName is XVoidTypeName && !javaTypeName.isBoxed
                val isNothing = javaTypeName is XVoidTypeName && javaTypeName.isNothing
                val toKotlinTypeName = if (javaResult.isErased || isNothing) kotlinTypeName else null
                IrType(
                    inKotlin = kotlinTypeName,
                    inJava = javaTypeName,
                    castContext = IrType.CastContext(toKotlin = toKotlinTypeName, toJava = null),
                    isReturnable = !isUnit,
                )
            }
            LoweredType(irType, kotlinResult.usedTypeParameterNames)
        }

        is TypeArgumentModel -> {
            val kotlinResult = toTypeName(forJava = false)
            val kotlinTypeName = kotlinResult.typeName
            val javaTypeName = toTypeName(forJava = true).typeName
            val isUnit = javaTypeName is XVoidTypeName && !javaTypeName.isBoxed
            val irType = IrType(
                inKotlin = kotlinTypeName,
                inJava = javaTypeName,
                castContext = IrType.CastContext(toKotlin = kotlinTypeName, toJava = null),
                isReturnable = !isUnit,
            )
            LoweredType(irType, kotlinResult.usedTypeParameterNames)
        }
    }

    private fun TypeModel.toTypeName(forJava: Boolean): TypeNameResult = when (this) {
        is ClassTypeModel -> toTypeName(forJava)
        is TypeArgumentModel -> {
            if (forJava) {
                val javaResult = boundType.toTypeName(forJava = true)
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
        val type = if (forJava) actualOrSelf else this
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
        is ClassTypeModel.GenericTypeArgument -> {
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
        val argumentTypeResults = arguments.map { it.toTypeName(forJava) }
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
}
