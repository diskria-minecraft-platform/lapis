package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.annotations.Env
import io.github.diskria.poetesse.interop.XClassName
import io.github.diskria.poetesse.interop.XTypeName

class IrMixin(
    val originatingFiles: List<KSFile>,
    val className: XClassName,
    val env: Env,
    val injections: List<Injection>,
    val duck: IrMixinDuck?,
    val targetClassName: XClassName?,
    val annotations: List<IrMixinAnnotation>,
) {
    class Injection(
        val jvmName: String,
        val extensionReceiverClassName: XClassName?,
        val mixinAnnotations: List<IrMixinAnnotation>,
        val isStatic: Boolean,
        val parameters: List<Parameter>,
        val returnTypeName: XTypeName?,
    ) {
        class Parameter(
            val name: String,
            val typeName: XTypeName,
            val mixinAnnotations: List<IrMixinAnnotation>,
        )
    }
}
