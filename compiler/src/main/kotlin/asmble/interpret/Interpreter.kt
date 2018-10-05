package asmble.interpret

import asmble.ast.Node

// This is not intended to be fast, rather clear and easy to read. Very little cached/memoized, lots of extra cycles.
class Interpreter {

    fun run(ctx: Context) {
        while (true) step(ctx)
    }

    fun step(ctx: Context) {
    }

    fun next(ctx: Context) {

    }

    fun invokeSingle(ctx: Context): StepResult = ctx.currFuncCtx.run {
        // TODO: validation
        func.instructions[insnIndex].let { insn ->
            when (insn) {
                is Node.Instr.Unreachable -> StepResult.Unreachable
                is Node.Instr.Nop -> next { }
                is Node.Instr.Block, is Node.Instr.Loop ->
                    next { blockStack += Block(insnIndex, insn, valueStack.size) }
                is Node.Instr.If -> {
                    blockStack += Block(insnIndex, insn, valueStack.size)
                    if (popInt() == 0) StepResult.Next else StepResult.Branch(1, true)
                }
                is Node.Instr.Else, is Node.Instr.End -> next { }
                is Node.Instr.Br -> StepResult.Branch(insn.relativeDepth)
                is Node.Instr.BrIf -> if (popInt() == 0) StepResult.Next else StepResult.Branch(insn.relativeDepth)
                is Node.Instr.BrTable -> StepResult.Branch(insn.targetTable.getOrNull(popInt()) ?: insn.default)
                is Node.Instr.Return -> StepResult.Return
                is Node.Instr.Call -> StepResult.Call(insn.index)
                is Node.Instr.CallIndirect -> StepResult.Call(popInt())
                is Node.Instr.Drop -> next { pop() }
                is Node.Instr.Select -> next {
                    popInt().also {
                        val v1 = pop()
                        val v2 = pop()
                        if (it == 0) push(v1) else push(v2)
                    }
                }
                is Node.Instr.GetLocal -> next { push(locals[insn.index]) }
                is Node.Instr.SetLocal -> next { locals[insn.index] = pop()}
                is Node.Instr.TeeLocal -> next { locals[insn.index] = peek() }
                is Node.Instr.GetGlobal -> next { push(ctx.getGlobal(insn.index)) }
                is Node.Instr.SetGlobal -> next { ctx.setGlobal(insn.index, pop()) }
                else -> TODO()
            }
        }
    }

    data class Context(
        val mod: Node.Module,
        val callStack: MutableList<FuncContext> = mutableListOf()
    ) {
        val currFuncCtx get() = callStack.last()
        fun getGlobal(index: Int): Number = TODO()
        fun setGlobal(index: Int, v: Number) { TODO() }
    }

    data class FuncContext(
        val func: Node.Func,
        val locals: MutableList<Number>,
        val valueStack: MutableList<Number>,
        val blockStack: MutableList<Block>,
        val insnIndex: Int
    ) {
        fun peek() = valueStack.last()
        fun pop() = valueStack.removeAt(valueStack.size - 1)
        fun popInt() = pop() as Int
        fun push(v: Number) { valueStack += v }

        inline fun next(crossinline f: () -> Unit): StepResult.Next { f(); return StepResult.Next }
    }

    data class Block(
        val startIndex: Int,
        val insn: Node.Instr,
        val stackSizeAtStart: Int
    )

    sealed class StepResult {
        object Next : StepResult()
        data class Branch(val blockDepth: Int, val tryElse: Boolean = false) : StepResult()
        data class Call(val funcIndex: Int) : StepResult()
        object Unreachable : StepResult()
        object Return : StepResult()
    }
}