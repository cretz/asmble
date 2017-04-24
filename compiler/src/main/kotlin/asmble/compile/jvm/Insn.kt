package asmble.compile.jvm

sealed class Insn {
    data class Node(val insn: asmble.ast.Node.Instr) : Insn()
    data class ImportFuncRefNeededOnStack(val index: Int) : Insn()
    data class ImportGlobalSetRefNeededOnStack(val index: Int) : Insn()
    object ThisNeededOnStack : Insn()
    object MemNeededOnStack : Insn()
}