package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.lapis.ksp.phases.lowering.models.IrMixin.Injection.Parameter
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class IrMixin(
    val sourceFile: KSFile?,
    val className: XClassName,
    val env: Env,
    val injections: List<Injection>,
    val duck: IrMixinDuck?,
    val targetTypeName: XTypeName,
    val annotations: List<IrMixinAnnotation>,
) {
    sealed interface Injection {

        val jvmName: String
        val mixinAnnotations: List<IrMixinAnnotation>
        val parameters: List<Parameter>
        val returnTypeName: XTypeName?

        class Parameter(
            val name: String,
            val typeName: XTypeName,
            val mixinAnnotations: List<IrMixinAnnotation>,
        )
    }

    class MemberInjection(
        override val jvmName: String,
        override val mixinAnnotations: List<IrMixinAnnotation>,
        override val parameters: List<Parameter>,
        override val returnTypeName: XTypeName?,
        val extensionReceiverType: IrTargetType?
    ) : Injection

    class StaticInjection(
        override val jvmName: String,
        override val mixinAnnotations: List<IrMixinAnnotation>,
        override val parameters: List<Parameter>,
        override val returnTypeName: XTypeName?,
        val patchCompanionName: String,
    ) : Injection
}
