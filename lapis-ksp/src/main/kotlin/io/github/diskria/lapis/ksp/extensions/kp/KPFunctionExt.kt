package io.github.diskria.lapis.ksp.extensions.kp

import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget
import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.phases.generator.builders.GenKotlinFunctionBody
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.kotlin.KPAnnotationBuilder
import io.github.diskria.poetesse.kotlin.KPFunctionBuilder
import io.github.diskria.poetesse.kotlin.KPParameter

inline fun <reified A : Annotation> KPFunctionBuilder.addAnnotation(
    useSiteTarget: UseSiteTarget? = null,
    builder: Builder<KPAnnotationBuilder> = {}
) {
    addAnnotation(buildKotlinAnnotation<A>(useSiteTarget, builder))
}

fun KPFunctionBuilder.setBody(builder: Builder<GenKotlinFunctionBody> = {}) {
    GenKotlinFunctionBody(this).builder()
}

fun KPFunctionBuilder.setReturnType(typeName: IrTypeName?) {
    returns(typeName?.kotlin.orUnit())
}

fun KPFunctionBuilder.setReceiverType(typeName: IrTypeName) {
    receiver(typeName.kotlin)
}

fun KPFunctionBuilder.addParameter(parameter: IrParameter) {
    KPParameter
        .builder(parameter.name, parameter.typeName.kotlin)
        .build()
        .also(::addParameter)
}

fun KPFunctionBuilder.setParameters(parameters: List<IrParameter>) {
    parameters.forEach(::addParameter)
}
