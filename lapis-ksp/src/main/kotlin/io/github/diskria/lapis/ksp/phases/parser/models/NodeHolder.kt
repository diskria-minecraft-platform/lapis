package io.github.diskria.lapis.ksp.phases.parser.models

import com.google.devtools.ksp.symbol.KSNode

interface NodeHolder {
    val node: KSNode
}
