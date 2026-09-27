package io.github.diskria.lapis.ksp.phases.validator.models

sealed interface DuckSourceModel {

    sealed interface Property : DuckSourceModel {
        val name: String
        val getterJvmName: String
        val setterJvmName: String?
        val type: TypeModel
    }

    sealed interface Function : DuckSourceModel {
        val name: String
        val jvmName: String
        val parameters: List<FunctionParameterModel>
        val returnType: TypeModel?
        val typeParameters: List<TypeParameterModel>
    }
}
