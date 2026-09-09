package io.github.diskria.lapis.ksp.phases.lowering.models

import com.google.devtools.ksp.symbol.KSFile
import io.github.diskria.lapis.ksp.phases.lowering.types.IrClassName
import io.github.diskria.poetesse.java.JPTypeKind
import io.github.diskria.poetesse.kotlin.KPTypeKind

interface IrBlueprint {
    val originatingFiles: List<KSFile>
}

abstract class IrKotlinClassBlueprint(val typeKind: KPTypeKind) : IrBlueprint {
    abstract val className: IrClassName
}

abstract class IrKotlinFileBlueprint(val packageName: String?, val fileName: String) : IrBlueprint

abstract class IrJavaFileBlueprint(val typeKind: JPTypeKind) : IrBlueprint {
    abstract val className: IrClassName
}

abstract class IrResourceBlueprint(val fileName: String) : IrBlueprint
