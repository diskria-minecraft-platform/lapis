package io.github.diskria.lapis.ksp.phases.lowering

import com.google.devtools.ksp.symbol.*
import io.github.diskria.lapis.ksp.KspOptions
import io.github.diskria.lapis.ksp.phases.lowering.models.*
import io.github.diskria.lapis.ksp.phases.validator.models.KMixinModel
import io.github.diskria.lapis.ksp.phases.validator.models.MixinAnnotation
import io.github.diskria.lapis.ksp.phases.validator.models.Type
import io.github.diskria.lapis.ksp.phases.validator.models.TypeParameter
import io.github.diskria.lapis.ksp.utils.JavaModifiers
import io.github.diskria.poetesse.Poetesse
import io.github.diskria.poetesse.interop.*
import io.github.diskria.poetesse.java.JPModifier
import java.util.*
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.*

class Lowering(private val options: KspOptions, private val poetesse: Poetesse) {

    fun lower(kMixins: List<KMixinModel>): List<FirKMixin> {
        val mixinSourcePackageLCP = if (!options.disableLCP) {
            kMixins.map { it.classDeclaration.packageName.asString() }.reduceOrNull { current, next ->
                val currentSegments = current.split('.')
                val nextSegments = next.split('.')
                currentSegments
                    .zip(nextSegments)
                    .takeWhile { (current, next) -> current == next }
                    .joinToString(".") { it.first }
            }.orEmpty()
        } else null
        return kMixins.map { it.lowerToFir(mixinSourcePackageLCP) }
    }

    private fun KMixinModel.lowerToFir(mixinSourcePackageLCP: String?): FirKMixin {
        val className = classDeclaration.lower()
        val typeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters = null)
        val mixin = deriveMixin(className, typeParameters, mixinSourcePackageLCP)
        return when (classKind) {
            is KMixinModel.Class -> {
                val constructorParameters = classKind.constructorParameters.map { it.lower(typeParameters) }
                FirKMixinClass(
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

            KMixinModel.Interface -> FirKMixinInterface(
                className = className,
                typeVariables = typeParameters.typeVariables,
                mixin = mixin,
            )
        }
    }

    private fun KMixinModel.deriveImpl(
        sourceClassName: XClassName,
        sourceTypeParameters: TypeParameters,
        constructorParameters: List<FirKMixinClass.ConstructorParameter>,
        duck: IrMixinDuck?,
    ) = IrKMixinImpl(
        originatingFile = containingFile,
        className = sourceClassName.withSuffix("_Impl"),
        typeVariables = sourceTypeParameters.typeVariables,
        constructorParameters = buildList {
            constructorParameters.firstNotNullOfOrNull { it as? FirKMixinClass.ConstructorParameter.Origin }?.let {
                add(IrKMixinImpl.ConstructorParameter.Instance(it.name, it.targetTypeCast))
            }
            if (duck != null && shadowSources.isNotEmpty()) {
                add(IrKMixinImpl.ConstructorParameter.Duck(duck.className))
            }
        },
    )

    private fun KMixinModel.deriveMixin(
        sourceClassName: XClassName,
        sourceTypeParameters: TypeParameters,
        sourcePackageLCP: String?,
    ) = IrMixin(
        originatingFile = containingFile,
        className = resolveMixinClassName(sourceClassName, sourcePackageLCP),
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
            val targetClassValue = IrMixinAnnotation.Argument.ClassValue(targetClassDeclaration.lower())
            val valueArgument = IrMixinAnnotation.ScalarArgument("value", targetClassValue)
            listOf(IrMixinAnnotation(poetesse.xClass(options.mixinAnnotation), listOf(valueArgument)))
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
        is KMixinModel.Class.ConstructorParameter.Origin -> FirKMixinClass.ConstructorParameter.Origin(
            name = name,
            targetTypeCast = type.lowerToTargetSubtypeCast(typeParameters),
        )
    }

    private fun KMixinModel.Extension.lower(enclosingTypeParameters: TypeParameters) = when (this) {
        is KMixinModel.Extension.Property -> IrMixinDuck.Extension.Property(
            type = type.lower(enclosingTypeParameters),
            sourceName = name,
            sourceGetterJvmName = getterJvmName,
            sourceSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            receiverType = receiverType.lower(enclosingTypeParameters),
        )

        is KMixinModel.Extension.Function -> {
            val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters)
            IrMixinDuck.Extension.Function(
                sourceName = name,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map {
                    IrFunctionParameter(
                        name = it.name,
                        type = it.type.lower(scopeTypeParameters),
                    )
                },
                returnType = returnType?.takeIf { !it.isUnit }?.lower(scopeTypeParameters),
                receiverType = receiverType.lower(scopeTypeParameters),
                typeVariables = scopeTypeParameters.typeVariables,
            )
        }
    }

    private fun KMixinModel.Shadow.lower(isInterface: Boolean, enclosingTypeParameters: TypeParameters) = when (this) {
        is KMixinModel.Shadow.Property -> IrMixinDuck.Shadow.Property(
            type = type.lower(enclosingTypeParameters),
            sourceName = name,
            sourceGetterJvmName = getterJvmName,
            sourceSetterJvmName = setterJvmName,
            getterName = getterJvmName.withUniqueModPrefix(),
            setterName = setterJvmName?.withUniqueModPrefix(),
            mappingName = mappingName,
            modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = true),
            mixinAnnotations = if (mixinAnnotations.isNotEmpty()) {
                mixinAnnotations.map { it.lower() }
            } else {
                listOfNotNull(
                    if (setterJvmName != null) options.mutableAnnotation else null,
                    if (FINAL in modifiers) options.finalAnnotation else null,
                    options.shadowAnnotation,
                ).map { IrMixinAnnotation(poetesse.xClass(it), emptyList()) }
            },
        )

        is KMixinModel.Shadow.Function -> {
            val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters)
            IrMixinDuck.Shadow.Function(
                sourceName = name,
                sourceJvmName = jvmName,
                name = jvmName.withUniqueModPrefix(),
                parameters = parameters.map {
                    IrFunctionParameter(
                        name = it.name,
                        type = it.type.lower(scopeTypeParameters),
                    )
                },
                returnType = returnType?.takeIf { !it.isUnit }?.lower(scopeTypeParameters),
                mappingName = mappingName,
                modifiers = modifiers.lowerToShadowModifiers(isInterface, isField = false),
                mixinAnnotations = if (mixinAnnotations.isNotEmpty()) {
                    mixinAnnotations.map { it.lower() }
                } else {
                    listOf(IrMixinAnnotation(poetesse.xClass(options.shadowAnnotation), emptyList()))
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
            mixinAnnotations = mixinAnnotations.map { it.lower() },
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(scopeTypeParameters),
                    parameter.mixinAnnotations.map { it.lower() },
                )
            },
            returnType = returnType?.takeIf { !it.isUnit }?.lower(scopeTypeParameters),
            extensionReceiverTargetTypeCast = extensionReceiverType?.lowerToTargetSubtypeCast(scopeTypeParameters),
            typeVariables = scopeTypeParameters.typeVariables,
        )
    }

    private fun KMixinModel.Injection.lowerAsStatic(companion: KMixinModel.CompanionObject): IrMixin.StaticInjection {
        val scopeTypeParameters = typeParameters.resolveTypeParameters(enclosingTypeParameters = null)
        return IrMixin.StaticInjection(
            sourceJvmName = jvmName,
            name = jvmName.withUniqueModPrefix(),
            mixinAnnotations = mixinAnnotations.map { it.lower() },
            parameters = parameters.map { parameter ->
                IrMixin.Injection.Parameter(
                    parameter.name,
                    parameter.type.lower(scopeTypeParameters),
                    parameter.mixinAnnotations.map { it.lower() },
                )
            },
            returnType = returnType?.takeIf { !it.isUnit }?.lower(scopeTypeParameters),
            kMixinCompanionObjectName = companion.name,
            typeVariables = scopeTypeParameters.typeVariables,
        )
    }

    private fun MixinAnnotation.lower() = IrMixinAnnotation(
        className = typeClassDeclaration.lower(),
        arguments = arguments.map { argument ->
            when (argument) {
                is MixinAnnotation.ScalarArgument -> IrMixinAnnotation.ScalarArgument(
                    name = argument.name,
                    value = argument.value.lower(),
                )

                is MixinAnnotation.ArrayArgument -> IrMixinAnnotation.ArrayArgument(
                    name = argument.name,
                    elements = argument.elements.map { it.lower() },
                )
            }
        },
    )

    private fun MixinAnnotation.Argument.Value.lower(): IrMixinAnnotation.Argument.Value = when (this) {
        is MixinAnnotation.Argument.BooleanValue -> IrMixinAnnotation.Argument.BooleanValue(boolean)
        is MixinAnnotation.Argument.ByteValue -> IrMixinAnnotation.Argument.ByteValue(byte)
        is MixinAnnotation.Argument.ShortValue -> IrMixinAnnotation.Argument.ShortValue(short)
        is MixinAnnotation.Argument.IntValue -> IrMixinAnnotation.Argument.IntValue(int)
        is MixinAnnotation.Argument.LongValue -> IrMixinAnnotation.Argument.LongValue(long)
        is MixinAnnotation.Argument.CharValue -> IrMixinAnnotation.Argument.CharValue(char)
        is MixinAnnotation.Argument.FloatValue -> IrMixinAnnotation.Argument.FloatValue(float)
        is MixinAnnotation.Argument.DoubleValue -> IrMixinAnnotation.Argument.DoubleValue(double)
        is MixinAnnotation.Argument.StringValue -> IrMixinAnnotation.Argument.StringValue(string)
        is MixinAnnotation.Argument.ClassValue -> IrMixinAnnotation.Argument.ClassValue(classDeclaration.lower())
        is MixinAnnotation.Argument.EnumValue -> IrMixinAnnotation.Argument.EnumValue(classDeclaration.lower(), name)
        is MixinAnnotation.Argument.AnnotationValue -> IrMixinAnnotation.Argument.AnnotationValue(annotation.lower())
    }

    private fun Type.lowerToTargetSubtypeCast(typeParameters: TypeParameters) = IrTargetSubtypeCast(
        type = lower(typeParameters),
        isUnsafeCastRequired = !isInterface,
        isTargetCastRequired = !isAny,
    )

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

    private fun List<TypeParameter>.resolveTypeParameters(enclosingTypeParameters: TypeParameters?): TypeParameters {
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

    private fun Type.lower(typeParameters: TypeParameters): IrType {
        val kotlinType = ksType.lower(typeParameters, arguments)
        val javaType = canonicalType?.let {
            it.ksType.lower(typeParameters, it.arguments)
        } ?: kotlinType
        return IrType(kotlin = kotlinType, java = javaType)
    }

    private fun KSType.lower(
        typeParameters: TypeParameters,
        arguments: List<Type.Argument>,
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
        if (raw || arguments.isEmpty()) {
            return typeName
        }
        if (typeName !is XClassName) return typeName
        val arguments = arguments.map { argument ->
            if (argument !is Type.TypedArgument) poetesse.xStar()
            else {
                val type = argument.type.ksType.lower(typeParameters, argument.type.arguments)
                when (argument) {
                    is Type.InvariantArgument -> type
                    is Type.CovariantArgument -> type.producer()
                    is Type.ContravariantArgument -> type.consumer()
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

    private fun KSClassDeclaration.lower(): XClassName {
        val packageName = packageName.asString()
        val typesString = requireNotNull(qualifiedName) {
            TODO("Guard this in validator")
        }.asString().removePrefix("$packageName.")
        val simpleNames = typesString.split(".")
        return poetesse.xClass(packageName, simpleNames)
    }

    private fun resolveMixinClassName(sourceClassName: XClassName, sourcePackageLCP: String?): XClassName {
        val sourcePackageName = sourceClassName.packageName
        val mixinPackageName = buildString {
            append(options.mixinPackage)
            options.mixinGeneratedSubpackage?.let { append(".$it") }
            if (sourcePackageName != null && sourcePackageLCP != null && sourcePackageName != sourcePackageLCP) {
                append(".${sourcePackageName.removePrefix("$sourcePackageLCP.")}")
            }
        }
        return poetesse.xClass(mixinPackageName, sourceClassName.simpleName).withSuffix("_Generated")
    }
}
