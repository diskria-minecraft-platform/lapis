package io.github.diskria.lapis.ksp.phases.validator.models

import com.google.devtools.ksp.containingFile
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import io.github.diskria.lapis.ksp.phases.lowering.toXClassName
import io.github.diskria.poetesse.interop.XClassName

open class SourceFile(
    symbol: KSNode,
    classDeclaration: KSClassDeclaration,
) {
    val className: XClassName = classDeclaration.toXClassName()
    val containingFile: KSFile? = symbol.containingFile
}
