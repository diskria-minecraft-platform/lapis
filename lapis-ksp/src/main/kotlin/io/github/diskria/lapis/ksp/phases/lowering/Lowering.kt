package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.*
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
            models.map { it.classDeclaration.packageName }.reduceOrNull { current, next ->
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
        val className = classDeclaration.lower()
        val typeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters = null)
        val mixin = deriveMixin(className, typeParameters)
        return when (classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower(typeParameters) }
                KMixinFirClass(
                    className = className,
                    typeVariables = typeParameters.typeVariables,
                    mixin = mixin,
                    impl = if (classKind.isAbstract) {
                        deriveImpl(className, typeParameters, constructorParameters, mixin.duck)
                    } else null,
                    constructorParameters = constructorParameters,
                    initStrategy = initStrategy,
                )
            }

            KMixinModel.Interface -> KMixinFirInterface(
                className = className,
                typeVariables = typeParameters.typeVariables,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel.deriveImpl(
        sourceClassName: XClassName,
        sourceTypeParameters: TypeParameters,
        constructorParameters: List<KMixinFirClass.ConstructorParameter>,
        duck: IrMixinDuck?,
    ) = IrKMixinImpl(
        originatingFile = containingFile,
        className = sourceClassName.withSuffix("_Impl"),
        typeVariables = sourceTypeParameters.typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? KMixinFirClass.ConstructorParameter.Origin }?.let {
                add(IrKMixinImpl.ConstructorParameter.Instance(it.name, it.type))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(IrKMixinImpl.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel.deriveMixin(sourceClassName: XClassName, sourceTypeParameters: TypeParameters) = IrMixin(
        originatingFile = containingFile,
        className = resolveMixinClassName(sourceClassName),
        typeVariables = sourceTypeParameters.typeVariables,
        side = side,
        injections = buildList {
            addAll(injections.map { it.lowerAsMember(sourceTypeParameters) })
            companionObject?.let { companion -> addAll(companion.injections.map { it.lowerAsStatic(companion) }) }
        },
        duck = deriveMixinDuck(sourceClassName, sourceTypeParameters),
        annotations = if (mixinAnnotations.isNotEmpty()) {
            mixinAnnotations.map { it.lower() }
        } else {
            val targetClassValue = IrAnnotation.Argument.ClassValue(targetClassDeclaration.lower())
            val valueArgument = IrAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
        },
    )

    private fun KMixinModel.deriveMixinDuck(sourceClassName: XClassName, typeParameters: TypeParameters): IrMixinDuck? {
        val shadows = shadowSources.map { it.lower(classKind is KMixinModel.Interface, typeParameters) }
        val extensions = extensionSources.map { it.lower(typeParameters) }
        return if (shadows.isNotEmpty() || extensions.isNotEmpty()) {
            IrMixinDuck(
                originatingFile = containingFile,
                className = sourceClassName.withSuffix("_Duck"),
                typeVariables = typeParameters.typeVariables,
                shadows = shadows,
                extensions = extensions,
            )
        } else null
    }

    private fun KMixinModel.Class.ConstructorParameter.lower(typeParameters: TypeParameters) = when (this) {
        is KMixinModel.Class.ConstructorParameter.Origin -> KMixinFirClass.ConstructorParameter.Origin(
            name = name,
            type = type.lower(typeParameters),
        )
    }

    private fun KMixinModel.Extension.lower(enclosingTypeParameters: TypeParameters) = when (this) {
        is KMixinModel.Extension.Property -> IrMixinDuck.Extension.Property(
            type = type.lower(enclosingTypeParameters),
            declaredName = declaredName,
            declaredGetterJvmName = getterJvmName,
            declaredSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            receiverType = receiverType.lower(enclosingTypeParameters),
        )

        is KMixinModel.Extension.Function -> {
            val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters)
            IrMixinDuck.Extension.Function(
                declaredName = declaredName,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map {
                    IrFunctionParameter(
                        name = it.name,
                        type = it.type.lower(scopeTypeParameters),
                    )
                },
                returnType = returnType?.lower(scopeTypeParameters),
                receiverType = receiverType.lower(scopeTypeParameters),
                typeVariables = scopeTypeParameters.typeVariables,
            )
        }
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean, enclosingTypeParameters: TypeParameters) = when (this) {
        is KMixinModel.Shadow.Property -> IrMixinDuck.Shadow.Property(
            type = type.lower(enclosingTypeParameters),
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

        is KMixinModel.Shadow.Function -> {
            val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters)
            IrMixinDuck.Shadow.Function(
                declaredName = declaredName,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map {
                    IrFunctionParameter(
                        name = it.name,
                        type = it.type.lower(scopeTypeParameters),
                    )
                },
                returnType = returnType?.lower(scopeTypeParameters),
                mappingName = mappingName,
                modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = false),
                annotations = if (mixinAnnotations.isNotEmpty()) {
                    mixinAnnotations.map { it.lower() }
                } else {
                    listOf(IrAnnotation(poetesse.xClass(options.shadowAnnotation)))
                },
                typeVariables = scopeTypeParameters.typeVariables,
            )
        }
    }

    private fun KMixinModel.Injection.lowerAsMember(enclosingTypeParameters: TypeParameters): IrMixin.MemberInjection {
        val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters)
        return IrMixin.MemberInjection(
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            annotations = mixinAnnotations.map { it.lower() },
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(scopeTypeParameters),
                    parameter.mixinAnnotations.map { it.lower() },
                )
            },
            returnType = returnType?.lower(scopeTypeParameters),
            extensionReceiverTargetTypeCast = extensionReceiverType?.lower(scopeTypeParameters),
            typeVariables = scopeTypeParameters.typeVariables,
        )
    }

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject): IrMixin.StaticInjection {
        val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters = null)
        return IrMixin.StaticInjection(
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            annotations = mixinAnnotations.map { it.lower() },
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(scopeTypeParameters),
                    parameter.mixinAnnotations.map { it.lower() },
                )
            },
            returnType = returnType?.lower(scopeTypeParameters),
            kMixinCompanionObjectName = companion.name,
            typeVariables = scopeTypeParameters.typeVariables,
        )
    }

    private fun MixinAnnotationModel.lower() = IrAnnotation(
        className = typeClassDeclaration.lower(),
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
        is MixinAnnotationModel.Argument.ClassValue -> IrAnnotation.Argument.ClassValue(classDeclaration.lower())
        is MixinAnnotationModel.Argument.EnumValue -> IrAnnotation.Argument.EnumValue(classDeclaration.lower(), name)
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

    class TypeParameters(private val parent: TypeParameters?) {

        val typeVariables: List<XTypeVariableName> get() = parametersMap.values.toList()
        val parametersMap = LinkedHashMap<String, XTypeVariableName>()

        operator fun get(index: String): XTypeVariableName =
            parametersMap[index] ?: parent?.get(index) ?: TODO("Guard this in validator")
    }

    private fun List<TypeParameterModel>.resolveTypeParameters(enclosingTypeParameters: TypeParameters?): TypeParameters {
        val scopeTypeParameters = TypeParameters(enclosingTypeParameters)
        forEach { scopeTypeParameters.parametersMap[it.name] = poetesse.xTypeVariable(it.name) }
        forEach { typeParameter ->
            scopeTypeParameters.parametersMap[typeParameter.name] = poetesse.xTypeVariable(
                name = typeParameter.name,
                bounds = typeParameter.bounds.map { it.ksType.lower(scopeTypeParameters, emptyList()) },
            )
        }
        return scopeTypeParameters
    }

    private fun TypeModel.lower(typeParameters: TypeParameters): IrType {
        val kotlinType = ksType.lower(typeParameters, arguments)
        val javaType = canonicalType?.let {
            it.ksType.lower(typeParameters, it.arguments)
        } ?: kotlinType
        return IrType(kotlin = kotlinType, java = javaType)
    }

    private fun KSType.lower(
        typeParameters: TypeParameters,
        arguments: List<TypeModel.Argument>,
        raw: Boolean = false,
    ): XTypeName {
        val declaration = declaration
        if (declaration is KSTypeParameter) {
            return typeParameters[declaration.name.asString()].nullable(isMarkedNullable)
        }
        val typeName = when (declaration) {
            is KSClassDeclaration, is KSTypeAlias -> declaration.lower(isMarkedNullable)
            else -> TODO("Guard this in validator")
        }
        if (typeName !is XClassName || raw || arguments.isEmpty()) {
            return typeName
        }
        val arguments = arguments.map { argument ->
            when (argument) {
                is TypeModel.StarArgument -> poetesse.xStar()
                is TypeModel.TypedArgument -> {
                    val type = argument.type.ksType.lower(typeParameters, argument.type.arguments)
                    when (argument) {
                        is TypeModel.InvariantArgument -> type
                        is TypeModel.CovariantArgument -> type.producer()
                        is TypeModel.ContravariantArgument -> type.consumer()
                    }
                }
            }
        }
        return typeName.generic(arguments, nullable = isMarkedNullable)
    }

    private fun KSDeclaration.lower(nullable: Boolean): XTypeName {
        val packageName = packageName.asString()
        val typesString = requireNotNull(qualifiedName) {
            TODO("Guard this in validator")
        }.asString().removePrefix("$packageName.")
        val simpleNames = typesString.split(".")
        return poetesse.xType(packageName, simpleNames, nullable)
    }

    private fun ClassDeclarationModel.lower(): XClassName =
        poetesse.xClass(packageName, qualifiedName.removePrefix("$packageName.").split("."))

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
