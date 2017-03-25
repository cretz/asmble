package asmble.compile.jvm

import asmble.ast.Node
import asmble.util.Logger

data class FuncContext(
    val cls: ClsContext,
    val node: Node.Func,
    val insns: List<Insn>,
    val memIsLocalVar: Boolean = false
) : Logger by cls.logger {
    fun actualLocalIndex(givenIndex: Int) =
        // Add 1 for "this"
        (node.type.params + node.locals).take(givenIndex).sumBy { it.typeRef.stackSize } + 1
}