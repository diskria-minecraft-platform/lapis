package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.symbol.KSType
import io.github.diskria.lapis.ksp.phases.lowering.toXTypeName
import io.github.diskria.poetesse.interop.XTypeName

sealed interface DuckSource {

    sealed interface Property : DuckSource {
        val name: String
        val getterJvmName: String
        val setterJvmName: String?
        val type: KSType

        val typeName: XTypeName get() = type.toXTypeName()
    }

    sealed interface Function : DuckSource {
        val name: String
        val jvmName: String
        val parameters: List<FunctionParameter>
        val returnType: KSType?

        val returnTypeName: XTypeName? get() = returnType?.toXTypeName()
    }
}
