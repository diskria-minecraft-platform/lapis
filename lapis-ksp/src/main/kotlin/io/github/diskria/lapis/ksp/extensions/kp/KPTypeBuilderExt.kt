package io.github.diskria.lapis.ksp.extensions.kp

import io.github.diskria.lapis.ksp.phases.lowering.models.IrParameter
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeName
import io.github.diskria.lapis.ksp.phases.lowering.types.IrTypeVariableName
import io.github.diskria.poetesse.kotlin.KPCodeBlock
import io.github.diskria.poetesse.kotlin.KPTypeBuilder

fun KPTypeBuilder.setConstructor(parameters: List<IrParameter>) {
    primaryConstructor(buildKotlinConstructor {
        setParameters(parameters)
    })
}

fun KPTypeBuilder.setConstructor(vararg parameters: IrParameter) {
    setConstructor(parameters.toList())
}

fun KPTypeBuilder.setSuperClass(
    typeName: IrTypeName,
    constructorArguments: List<KPCodeBlock> = emptyList()
) {
    superclass(typeName.kotlin)
    constructorArguments.forEach {
        addSuperclassConstructorParameter(it)
    }
}

fun KPTypeBuilder.addSuperInterface(typeName: IrTypeName) {
    addSuperinterface(typeName.kotlin)
}

fun KPTypeBuilder.setVariableTypes(vararg types: IrTypeVariableName) {
    addTypeVariables(types.map { it.kotlin })
}
