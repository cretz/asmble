package asmble.compile.jvm

import asmble.ast.Node

data class FuncContext(
    val cls: ClsContext,
    val node: Node.Func,
    val insns: List<Insn>
) {
    fun actualLocalIndex(givenIndex: Int) =
        // Add 1 for "this"
        node.locals.take(givenIndex).sumBy { it.typeRef.stackSize } + 1
}