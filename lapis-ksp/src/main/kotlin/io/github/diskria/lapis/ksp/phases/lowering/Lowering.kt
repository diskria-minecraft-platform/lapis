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
        sourceClassName: XClassName,
        typeVariables: List<XTypeVariableName>,
        constructorParameters: List<KMixinFirClass.ConstructorParameter>,
        duck: IrMixinDuck?,
    ) = IrKMixinImpl(
        originatingFile = containingFile,
        className = sourceClassName.withSuffix("_Impl"),
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

    private fun KMixinModel.deriveMixin(sourceClassName: XClassName) = IrMixin(
        originatingFile = containingFile,
        className = resolveMixinClassName(sourceClassName),
        side = side,
        injections = buildList {
            addAll(injections.map { it.lowerAsMember() })
            companionObject?.let { companion -> addAll(companion.injections.map { it.lowerAsStatic(companion) }) }
        },
        duck = deriveMixinDuck(sourceClassName),
        annotations = if (mixinAnnotations.isNotEmpty()) {
            mixinAnnotations.map { it.lower() }
        } else {
            val targetClassValue = IrAnnotation.Argument.ClassValue(targetType.toXClassName())
            val valueArgument = IrAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel.deriveMixinDuck(sourceClassName: XClassName): IrMixinDuck? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface) }
        val extensions = extensionSources.map { it.lower(targetType, typeParameters) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                originatingFile = containingFile,
                className = sourceClassName.withSuffix("_Duck"),
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Class.ConstructorParameter.lower() = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> KMixinFirClass.ConstructorParameter.Origin(
            name = name,
            type = type.lower(),
        )
    }

    private fun KMixinModel.Extension.lower(
        targetType: ClassTypeModel,
        enclosingTypeParameters: List<TypeParameterModel>,
    ) = when (this) {
        is KMixinModel.Extension.Property -> IrMixinDuck.Extension.Property(
            type = type.lower(),
            declaredName = declaredName,
            declaredGetterJvmName = getterJvmName,
            declaredSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            receiverType = targetType.lower(),
        )

        is KMixinModel.Extension.Function -> {
            val loweredParameters = parameters.map { IrFunctionParameter(name = it.name, type = it.type.lower()) }
            val loweredReturnType = returnType?.lower()
            val functionTypeParameterNames = typeParameters.mapTo(mutableSetOf()) { it.name }
            val unshadowedEnclosingTypeParameterNames = enclosingTypeParameters
                .mapTo(mutableSetOf()) { it.name }
                .apply { removeAll(functionTypeParameterNames) }
            val usedTypeParameterNames = buildSet {
                loweredParameters.forEach { addAll(it.type.usedTypeParameterNames) }
                loweredReturnType?.let { addAll(it.usedTypeParameterNames) }
            }
            val activeReceiverTypeParameterNames = usedTypeParameterNames.filterTo(mutableSetOf()) {
                it in unshadowedEnclosingTypeParameterNames
            }
            val loweredReceiverType = targetType.lowerAsReceiver(
                enclosingTypeParameters = enclosingTypeParameters,
                usedTypeParameterNames = activeReceiverTypeParameterNames,
            )
            val usedEnclosingTypeParameters = enclosingTypeParameters.filter {
                it.name in activeReceiverTypeParameterNames
            }
            val requiredTypeParameters = usedEnclosingTypeParameters + typeParameters
            IrMixinDuck.Extension.Function(
                declaredName = declaredName,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = loweredParameters,
                returnType = loweredReturnType,
                receiverType = loweredReceiverType,
                typeVariables = requiredTypeParameters.lower(),
            )
        }
    }

    private fun ClassTypeModel.lowerAsReceiver(
        enclosingTypeParameters: List<TypeParameterModel>,
        usedTypeParameterNames: Set<String>,
    ): IrType {
        if (enclosingTypeParameters.isEmpty()) return lower()
        val substitutedArguments = enclosingTypeParameters.map { param ->
            if (param.name in usedTypeParameterNames) {
                ClassTypeModel.GenericTypeArgument(
                    type = TypeArgumentModel(
                        name = param.name,
                        canonicalType = ClassTypeModel.ANY,
                        isNullable = false,
                    ),
                    variance = VarianceType.INVARIANT,
                )
            } else {
                ClassTypeModel.StarProjectionArgument
            }
        }
        return ClassTypeModel(
            packageName = packageName,
            qualifiedName = qualifiedName,
            arguments = substitutedArguments,
            canonicalType = canonicalType,
            isNullable = isNullable,
        ).lower()
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean) = when (this) {
        is KMixinModel.Shadow.Property -> IrMixinDuck.Shadow.Property(
            type = type.lower(),
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
                    type = it.type.lower(),
                )
            },
            returnType = returnType?.lower(),
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
        parameters = parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.type.lower(),
                parameter.mixinAnnotations.map { it.lower() },
            )
        },
        returnType = returnType?.lower(),
        extensionReceiverTargetTypeCast = extensionReceiverType?.lower(),
    )

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject) = IrMixin.StaticInjection(
        sourceJvmName = jvmName,
        name = jvmName.withUniqueModPrefix(),
        annotations = mixinAnnotations.map { it.lower() },
        parameters = parameters.map { parameter ->
            IrMixin.Injection.Parameter(
                parameter.name,
                parameter.type.lower(),
                parameter.mixinAnnotations.map { it.lower() },
            )
        },
        returnType = returnType?.lower(),
        kMixinCompanionObjectName = companion.name,
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
                bounds = typeParameter.bounds.map { it.lower().inKotlin },
            )
        }

    private class XTypeResult(
        val typeName: XTypeName,
        val erased: Boolean = false,
        val usedTypeParameterNames: Set<String> = emptySet(),
    )

    private fun TypeModel.lower(): IrType = when (this) {
        is ClassTypeModel -> {
            val kotlinResult = toXTypeName(forJava = false)
            val javaResult = toXTypeName(forJava = true)
            IrType(
                inKotlin = kotlinResult.typeName,
                inJava = javaResult.typeName,
                isJavaErased = javaResult.erased,
                usedTypeParameterNames = kotlinResult.usedTypeParameterNames,
            )
        }

        is TypeArgumentModel -> {
            val kotlinResult = toXTypeName(forJava = false)
            val javaResult = toXTypeName(forJava = true)
            IrType(
                inKotlin = kotlinResult.typeName,
                inJava = javaResult.typeName,
                isJavaErased = true,
                usedTypeParameterNames = kotlinResult.usedTypeParameterNames,
            )
        }
    }

    private fun TypeModel.toXTypeName(forJava: Boolean): XTypeResult = when (this) {
        is ClassTypeModel -> toXTypeName(forJava)
        is TypeArgumentModel -> {
            if (forJava) {
                val canonicalResult = canonicalType.toXTypeName(forJava = true)
                XTypeResult(canonicalResult.typeName, erased = true)
            } else {
                XTypeResult(
                    typeName = poetesse.xTypeVariable(name = name, nullable = isNullable),
                    usedTypeParameterNames = setOf(name),
                )
            }
        }
    }

    private fun ClassTypeModel.toXTypeName(forJava: Boolean): XTypeResult {
        val type = if (forJava && canonicalType != null) canonicalType else this
        val detected = type.detectTypeName(forJava)
        if (forJava && detected.typeName is XParameterizedTypeName) {
            return XTypeResult(
                typeName = detected.typeName.rawType,
                erased = true,
                usedTypeParameterNames = detected.usedTypeParameterNames,
            )
        }
        return detected
    }

    private fun ClassTypeModel.TypeArgument.toXTypeName(forJava: Boolean): XTypeResult = when (this) {
        is ClassTypeModel.StarProjectionArgument -> XTypeResult(poetesse.xStar())
        is ClassTypeModel.GenericTypeArgument -> {
            val result = type.toXTypeName(forJava)
            val typeName = when (variance) {
                VarianceType.INVARIANT -> result.typeName
                VarianceType.COVARIANT -> result.typeName.producer()
                VarianceType.CONTRAVARIANT -> result.typeName.consumer()
            }
            XTypeResult(
                typeName = typeName,
                erased = result.erased,
                usedTypeParameterNames = result.usedTypeParameterNames,
            )
        }
    }

    private fun ClassTypeModel.detectTypeName(forJava: Boolean): XTypeResult {
        val argumentResults = arguments.map { it.toXTypeName(forJava) }
        val xType = poetesse.xType(
            packageName = packageName,
            simpleNames = qualifiedName.removePrefix("$packageName.").split("."),
            typeArguments = argumentResults.map { it.typeName },
            nullable = isNullable,
        )
        return XTypeResult(
            typeName = xType,
            erased = argumentResults.any { it.erased },
            usedTypeParameterNames = argumentResults.flatMapTo(mutableSetOf()) { it.usedTypeParameterNames },
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
