package io.github.diskria.lapis.ksp.phases.validator.models

sealed interface DuckSourceModel {

    sealed interface Property : DuckSourceModel {
        val declaredName: String
        val getterJvmName: String
        val setterJvmName: String?
        val type: TypeModel
    }

    sealed interface Function : DuckSourceModel {
        val declaredName: String
        val jvmName: String
        val parameters: List<FunctionParameterModel>
        val returnType: TypeModel?
        val typeParameters: List<TypeParameterModel>
    }
}
