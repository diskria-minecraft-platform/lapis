package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.phases.generator.GeneratorConstants
import io.github.diskria.lapis.ksp.phases.generator.builders.IrJavaCodeBlock
import io.github.diskria.lapis.ksp.phases.lowering.IrVisibilityModifier
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.*

val JPString: JPClassName = JPClassName.get(String::class.java)
val JPList: JPClassName = JPClassName.get(List::class.java)
val JPSet: JPClassName = JPClassName.get(Set::class.java)
val JPMap: JPClassName = JPClassName.get(Map::class.java)

inline fun <reified A : Annotation> buildJavaAnnotation(builder: Builder<JPAnnotationBuilder> = {}): JPAnnotation =
    JPAnnotation.builder(JPClassName.get(A::class.java)).apply(builder).build()

fun buildJavaCodeBlock(builder: Builder<IrJavaCodeBlock> = {}): JPCodeBlock =
    IrJavaCodeBlock(JPCodeBlock.builder()).apply(builder).build()

fun buildJavaCodeBlock(
    format: String,
    argumentsBuilder: Builder<IrJavaCodeBlock.Arguments> = {}
): JPCodeBlock =
    buildJavaCodeBlock {
        add(format, argumentsBuilder)
    }

fun buildJavaField(
    name: String,
    typeName: IrTypeName,
    visibility: IrVisibilityModifier? = IrVisibilityModifier.PUBLIC,
    builder: Builder<JPFieldBuilder> = {}
): JPField =
    JPField.builder(typeName.java, name).apply {
        visibility?.let { addModifiers(it.java) }
        builder()
    }.build()

fun buildJavaMethod(
    name: String,
    visibility: IrVisibilityModifier? = IrVisibilityModifier.PUBLIC,
    builder: Builder<JPMethodBuilder> = {}
): JPMethod =
    JPMethod.methodBuilder(name).apply {
        visibility?.let { addModifiers(it.java) }
        builder()
    }.build()

fun buildJavaParameter(
    name: String,
    typeName: IrTypeName,
    builder: Builder<JPParameterBuilder> = {}
): JPParameter =
    JPParameter.builder(typeName.java, name).apply(builder).build()

fun buildJavaInterface(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<JPTypeBuilder> = {}
): JPType =
    JPType.interfaceBuilder(name).apply {
        addModifiers(visibility.java)
        builder()
    }.build()

fun buildJavaClass(
    name: String,
    visibility: IrVisibilityModifier = IrVisibilityModifier.PUBLIC,
    builder: Builder<JPTypeBuilder> = {}
): JPType =
    JPType.classBuilder(name).apply {
        addModifiers(visibility.java)
        builder()
    }.build()

fun buildJavaFile(className: IrClassName, builder: () -> JPType): JPFile =
    JPFile
        .builder(className.packageName, builder())
        .addFileComment(GeneratorConstants.GENERATED_HEADER)
        .indent(GeneratorConstants.INDENT)
        .build()
