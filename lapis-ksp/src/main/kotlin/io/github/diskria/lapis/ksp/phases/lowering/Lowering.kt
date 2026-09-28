package io.github.diskria.lapis.ksp.phases.lowering

import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.*
import io.github.diskria.lapis.ksp.utils.JavaModifiers
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
        val mixin = deriveMixin(className, typeVariables)
        return when (classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower() }
                KMixinFirClass(
                    className = className,
                    typeVariables = typeVariables,
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
                typeVariables = typeVariables,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel.deriveImpl(
        sourceClassName: XClassName,
        typeVariables: IrTypeVariables,
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

    private fun KMixinModel.deriveMixin(sourceClassName: XClassName, typeVariables: IrTypeVariables) = IrMixin(
        originatingFile = containingFile,
        className = resolveMixinClassName(sourceClassName),
        typeVariables = typeVariables,
        side = side,
        injections = buildList {
            addAll(injections.map { it.lowerAsMember() })
            companionObject?.let { companion -> addAll(companion.injections.map { it.lowerAsStatic(companion) }) }
        },
        duck = deriveMixinDuck(sourceClassName, typeVariables),
        annotations = if (mixinAnnotations.isNotEmpty()) {
            mixinAnnotations.map { it.lower() }
        } else {
            val targetClassValue = IrAnnotation.Argument.ClassValue(targetClassDeclaration.toXClassName())
            val valueArgument = IrAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel.deriveMixinDuck(sourceClassName: XClassName, typeVariables: IrTypeVariables): IrMixinDuck? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface) }
        val extensions = extensionSources.map { it.lower() }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                originatingFile = containingFile,
                className = sourceClassName.withSuffix("_Duck"),
                typeVariables = typeVariables,
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

    private fun KMixinModel.Extension.lower() = when (this) {
        is KMixinModel.Extension.Property -> IrMixinDuck.Extension.Property(
            type = type.lower(),
            declaredName = declaredName,
            declaredGetterJvmName = getterJvmName,
            declaredSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            receiverType = receiverType.lower(),
        )

        is KMixinModel.Extension.Function -> IrMixinDuck.Extension.Function(
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
            receiverType = receiverType.lower(),
            typeVariables = typeParameters.lower(),
        )
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
        typeVariables = typeParameters.lower(),
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
        typeVariables = typeParameters.lower(),
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

    private fun List<TypeParameterModel>.lower(): IrTypeVariables {
        if (isEmpty()) return IrTypeVariables(emptyList())
        val inKotlin = ArrayList<XTypeVariableName>(size)
        val inJava = ArrayList<XTypeVariableName>(size)
        forEach { typeParameter ->
            val bounds = typeParameter.bounds.map { it.lower() }
            inKotlin += poetesse.xTypeVariable(typeParameter.name, bounds.map { it.inKotlin })
            inJava += poetesse.xTypeVariable(typeParameter.name, bounds.map { it.inJava })
        }
        return IrTypeVariables(inKotlin, inJava)
    }

    private fun TypeModel.lower(): IrType = when (this) {
        is ClassTypeModel -> {
            val inKotlin = toXTypeName(forJava = false)
            val inJava = if (hasJavaDivergence()) {
                toXTypeName(forJava = true)
            } else {
                inKotlin
            }
            IrType(inKotlin, inJava)
        }

        is TypeArgumentModel -> IrType(toXTypeName(forJava = false))
    }

    private fun TypeModel.hasJavaDivergence(): Boolean =
        this is ClassTypeModel && (canonicalType != null || arguments.any { argument ->
            when (argument) {
                is ClassTypeModel.StarArgument -> false
                is ClassTypeModel.InvariantArgument -> argument.type.hasJavaDivergence()
                is ClassTypeModel.CovariantArgument -> argument.type.hasJavaDivergence()
                is ClassTypeModel.ContravariantArgument -> argument.type.hasJavaDivergence()
            }
        })

    private fun TypeModel.toXTypeName(forJava: Boolean): XTypeName = when (this) {
        is ClassTypeModel -> {
            val type = if (forJava && canonicalType != null) canonicalType else this
            val typeName = type.detectTypeName()
            if (typeName is XClassName && type.arguments.isNotEmpty()) {
                typeName.generic(type.arguments.map { it.toXTypeName(forJava) })
            } else {
                typeName
            }
        }

        is TypeArgumentModel -> poetesse.xTypeVariable(name, nullable = isNullable)
    }

    private fun ClassTypeModel.Argument.toXTypeName(forJava: Boolean): XTypeName = when (this) {
        is ClassTypeModel.StarArgument -> poetesse.xStar()
        is ClassTypeModel.InvariantArgument -> type.toXTypeName(forJava)
        is ClassTypeModel.CovariantArgument -> type.toXTypeName(forJava).producer()
        is ClassTypeModel.ContravariantArgument -> type.toXTypeName(forJava).consumer()
    }

    private fun ClassTypeModel.detectTypeName(): XTypeName =
        poetesse.xType(packageName, qualifiedName.removePrefix("$packageName.").split("."), nullable = isNullable)

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
