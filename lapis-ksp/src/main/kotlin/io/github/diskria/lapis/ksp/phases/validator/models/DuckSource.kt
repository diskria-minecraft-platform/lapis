package io.github.diskria.lapis.ksp.phases.validator.models

sealed interface DuckSource {

    sealed interface Property : DuckSource {
        val name: String
        val getterJvmName: String
        val setterJvmName: String?
        val type: Type
    }

    sealed interface Function : DuckSource {
        val name: String
        val jvmName: String
        val parameters: List<FunctionParameter>
        val returnType: Type?
        val typeParameters: List<TypeParameter>
    }
}
