package io.github.diskria.lapis.core.parser.models

interface Node {
    fun report(message: String)
}

interface NodeHolder {
    val node: Node
}
