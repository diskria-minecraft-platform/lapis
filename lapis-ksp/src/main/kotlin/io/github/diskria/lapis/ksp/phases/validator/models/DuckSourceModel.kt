package io.github.diskria.lapis.ksp.phases.validator.models

sealed interface DuckSourceModel {

    val declaredName: String

    sealed interface Property : DuckSourceModel {
        val getterJvmName: String
        val setterJvmName: String?
        val type: TypeModel
    }

    sealed interface Function : DuckSourceModel {
        val jvmName: String
        val parameters: List<FunctionParameterModel>
        val returnType: TypeModel?
        val typeParameters: List<TypeParameterModel>
    }
}
