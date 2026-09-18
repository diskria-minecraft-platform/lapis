package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSType

sealed interface DuckSource {

    sealed interface Property : DuckSource {
        val name: String
        val getterJvmName: String
        val setterJvmName: String?
        val type: KSType
    }

    sealed interface Function : DuckSource {
        val name: String
        val jvmName: String
        val parameters: List<FunctionParameter>
        val returnType: KSType?
    }
}
