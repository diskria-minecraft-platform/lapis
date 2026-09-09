package io.github.diskria.lapis.ksp.extensions.jp

import io.github.diskria.lapis.ksp.extensions.common.Builder
import io.github.diskria.lapis.ksp.phases.generator.builders.GenJavaMethodBody
import io.github.diskria.lapis.ksp.phases.lowering.asIrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.poetesse.java.JPAnnotationBuilder
import io.github.diskria.poetesse.java.JPMethodBuilder
import io.github.diskria.poetesse.java.JPParameter

inline fun <reified A : Annotation> JPMethodBuilder.addAnnotation(builder: Builder<JPAnnotationBuilder> = {}) {
    addAnnotation(buildJavaAnnotation<A>(builder))
}

fun JPMethodBuilder.setBody(builder: Builder<GenJavaMethodBody> = {}) {
    GenJavaMethodBody(this).builder()
}

fun JPMethodBuilder.setStubBody(message: String = "Stub!") {
    setBody { throw_("new %T(%S)") { +AssertionError::class.asIrTypeName(); +message } }
}

fun JPMethodBuilder.setReturnType(typeName: IrTypeName?) {
    returns(typeName?.java.orVoid())
}

fun JPMethodBuilder.addParameter(parameter: IrParameter): JPParameter =
    JPParameter
        .builder(parameter.typeName.java, parameter.name)
        .build()
        .also(::addParameter)

fun JPMethodBuilder.setParameters(parameters: List<IrParameter>): List<JPParameter> =
    parameters.map(::addParameter)
