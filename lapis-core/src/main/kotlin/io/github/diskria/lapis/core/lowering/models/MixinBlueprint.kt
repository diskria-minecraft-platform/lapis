package io.github.diskria.lapis.core.lowering.models

import io.github.diskria.lapis.annotations.Side
import io.github.diskria.lapis.core.lowering.models.MixinBlueprint.Injection.Parameter
import io.github.diskria.poetesse.interop.XClassName

class MixinBlueprint<O>(
    val origin: O?,
    val className: XClassName,
    val side: Side,
    val injections: List<Injection>,
    val duck: MixinDuckBlueprint<O>?,
    val annotations: List<AnnotationBlueprint>,
) {
    sealed interface Injection {

        val name: String
        val sourceJvmName: String
        val annotations: List<AnnotationBlueprint>
        val parameters: List<Parameter>
        val contextParameters: List<Parameter>
        val returnType: TypeBlueprint?

        class Parameter(
            val name: String,
            val type: TypeBlueprint,
            val annotations: List<AnnotationBlueprint>,
        )
    }

    class MemberInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val annotations: List<AnnotationBlueprint>,
        override val parameters: List<Parameter>,
        override val contextParameters: List<Parameter>,
        override val returnType: TypeBlueprint?,
        val extensionReceiverType: TypeBlueprint?
    ) : Injection

    class StaticInjection(
        override val name: String,
        override val sourceJvmName: String,
        override val annotations: List<AnnotationBlueprint>,
        override val parameters: List<Parameter>,
        override val contextParameters: List<Parameter>,
        override val returnType: TypeBlueprint?,
        val kMixinCompanionObjectName: String,
    ) : Injection
}
