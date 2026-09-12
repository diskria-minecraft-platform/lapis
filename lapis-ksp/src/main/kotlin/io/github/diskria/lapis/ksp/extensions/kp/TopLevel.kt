package io.github.diskria.lapis.ksp.extensions.kp

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget
import io.github.diskria.lapis.ksp.extensions.capitalize
import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.phases.generator.GeneratorConstants
import io.github.diskria.lapis.ksp.phases.generator.builders.IrKotlinCodeBlock
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.kotlin.*

val KPString: KPClassName = STRING
val KPList: KPClassName = LIST
val KPSet: KPClassName = SET
val KPMap: KPClassName = MAP

val KPBooleanArray: KPClassName = BOOLEAN_ARRAY
val KPByteArray: KPClassName = BYTE_ARRAY
val KPShortArray: KPClassName = SHORT_ARRAY
val KPIntArray: KPClassName = INT_ARRAY
val KPLongArray: KPClassName = LONG_ARRAY
val KPCharArray: KPClassName = CHAR_ARRAY
val KPFloatArray: KPClassName = FLOAT_ARRAY
val KPDoubleArray: KPClassName = DOUBLE_ARRAY

inline fun <reified A : Annotation> buildKotlinAnnotation(
    useSiteTarget: UseSiteTarget? = null,
    builder: Builder<KPAnnotationBuilder> = {}
): KPAnnotation =
    KPAnnotation.builder(A::class).apply {
        useSiteTarget(useSiteTarget)
        builder()
    }.build()

fun buildKotlinCodeBlock(builder: Builder<IrKotlinCodeBlock> = {}): KPCodeBlock =
    IrKotlinCodeBlock(KPCodeBlock.builder()).apply(builder).build()

fun buildKotlinCodeBlock(
    format: String,
    argumentsBuilder: Builder<IrKotlinCodeBlock.Arguments> = {}
): KPCodeBlock =
    buildKotlinCodeBlock {
        add(format, argumentsBuilder)
    }

fun buildKotlinProperty(
    name: String,
    typeName: IrTypeName,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    jvmNamespace: IrClassName? = null,
    builder: KPPropertyBuilder.(propertyName: String) -> Unit = {}
): KPProperty {
    val initialBuilder = KPProperty.builder(name, typeName.kotlin).apply {
        addModifiers(visibility.kotlin)
        builder(name)
    }
    val property = initialBuilder.build()
    if (jvmNamespace == null) {
        return property
    }
    val finalBuilder = property.toBuilder()
    val useSiteTargets = buildList {
        add(UseSiteTarget.GET)
        if (property.mutable) {
            add(UseSiteTarget.SET)
        }
    }
    useSiteTargets.forEach { useSiteTarget ->
        finalBuilder.addAnnotation<JvmName>(useSiteTarget) {
            val suffix = if (useSiteTargets.size == 1) {
                name
            } else {
                useSiteTarget.name.lowercase() + name.capitalize()
            }
            setArgumentValue(JvmName::name, jvmNamespace.derived(suffix).simpleName)
        }
    }
    return finalBuilder.build()
}

fun buildKotlinGetter(builder: Builder<KPFunctionBuilder> = {}): KPFunction =
    KPFunction.getterBuilder().apply(builder).build()

fun buildKotlinSetter(builder: Builder<KPFunctionBuilder> = {}): KPFunction =
    KPFunction.setterBuilder().apply(builder).build()

fun buildKotlinFunction(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    jvmNamespace: IrClassName? = null,
    builder: KPFunctionBuilder.(functionName: String) -> Unit = {}
): KPFunction =
    KPFunction.builder(name).apply {
        jvmNamespace?.let {
            addAnnotation<JvmName> {
                setArgumentValue(JvmName::name, jvmNamespace.derived(name).simpleName)
            }
        }
        addModifiers(visibility.kotlin)
        builder(name)
    }.build()

fun buildKotlinParameter(
    name: String,
    typeName: IrTypeName,
    builder: Builder<KPParameterBuilder> = {}
): KPParameter =
    KPParameter.builder(name, typeName.kotlin).apply(builder).build()

fun buildKotlinParameter(
    parameter: IrParameter,
    builder: Builder<KPParameterBuilder> = {}
): KPParameter =
    buildKotlinParameter(parameter.name, parameter.typeName, builder)

fun buildKotlinInterface(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<KPTypeBuilder> = {}
): KPType =
    KPType.interfaceBuilder(name).apply {
        addModifiers(visibility.kotlin)
        builder()
    }.build()

fun buildKotlinConstructor(
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<KPFunctionBuilder> = {}
): KPFunction =
    KPFunction.constructorBuilder().apply {
        addModifiers(visibility.kotlin)
        builder()
    }.build()

fun buildKotlinClass(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<KPTypeBuilder> = {}
): KPType =
    KPType.classBuilder(name).apply {
        addModifiers(visibility.kotlin)
        builder()
    }.build()

fun buildKotlinObject(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<KPTypeBuilder> = {}
): KPType =
    KPType.objectBuilder(name).apply {
        addModifiers(visibility.kotlin)
        builder()
    }.build()

fun buildKotlinFile(
    packageName: String?,
    fileName: String,
    builder: Builder<KPFileBuilder> = {}
): KPFile =
    KPFile.builder(packageName.orEmpty(), fileName)
        .addFileComment(GeneratorConstants.GENERATED_HEADER)
        .apply(builder)
        .indent(GeneratorConstants.INDENT)
        .build()

fun buildKotlinFile(className: IrClassName, builder: Builder<KPFileBuilder> = {}): KPFile =
    buildKotlinFile(className.packageName, className.nestedName, builder)
