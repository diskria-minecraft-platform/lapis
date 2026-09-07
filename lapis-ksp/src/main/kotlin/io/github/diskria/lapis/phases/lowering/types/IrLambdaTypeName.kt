package io.github.diskria.lapis.phases.lowering.types

import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import io.github.diskria.lapis.extensions.common.lapisError
import io.github.diskria.lapis.extensions.kp.buildKotlinParameter
import io.github.diskria.lapis.extensions.kp.orUnit
import io.github.diskria.lapis.extensions.quoted
import io.github.diskria.lapis.phases.lowering.models.IrParameter
import io.github.diskria.poetesse.java.JPTypeName
import io.github.diskria.poetesse.kotlin.KPFunctionalTypeName

class IrLambdaTypeName(override val kotlin: KPFunctionalTypeName) : IrTypeName(kotlin) {

    override val java: JPTypeName
        get() = lapisError(
            "Lambda type ${kotlin.toString().quoted()} is not supported in Java, " +
                "but was leaked into IR"
        )

    companion object {
        @OptIn(ExperimentalKotlinPoetApi::class)
        fun of(
            receiverTypeName: IrTypeName? = null,
            parameters: List<IrParameter> = emptyList(),
            returnTypeName: IrTypeName? = null,
            contextParameters: List<IrTypeName> = emptyList(),
        ): IrLambdaTypeName =
            IrLambdaTypeName(
                KPFunctionalTypeName.get(
                    receiver = receiverTypeName?.kotlin,
                    parameters = parameters.map(::buildKotlinParameter),
                    returnType = returnTypeName?.kotlin.orUnit(),
                    contextParameters = contextParameters.map { it.kotlin }
                )
            )
    }
}
