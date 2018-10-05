package asmble.interpret

import asmble.ast.Node
import asmble.compile.jvm.Mem
import asmble.util.toUnsignedInt
import asmble.util.toUnsignedLong
import asmble.util.toUnsignedShort
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
                is Node.Instr.I32Load -> next { push(ctx.mem.getInt(insn.popMemAddr())) }
                is Node.Instr.I64Load -> next { push(ctx.mem.getLong(insn.popMemAddr())) }
                is Node.Instr.F32Load -> next { push(ctx.mem.getFloat(insn.popMemAddr())) }
                is Node.Instr.F64Load -> next { push(ctx.mem.getDouble(insn.popMemAddr())) }
                is Node.Instr.I32Load8S -> next { push(ctx.mem.get(insn.popMemAddr()).toInt()) }
                is Node.Instr.I32Load8U -> next { push(ctx.mem.get(insn.popMemAddr()).toUnsignedInt()) }
                is Node.Instr.I32Load16S -> next { push(ctx.mem.getShort(insn.popMemAddr()).toInt()) }
                is Node.Instr.I32Load16U -> next { push(ctx.mem.getShort(insn.popMemAddr()).toUnsignedInt()) }
                is Node.Instr.I64Load8S -> next { push(ctx.mem.get(insn.popMemAddr()).toLong()) }
                is Node.Instr.I64Load8U -> next { push(ctx.mem.get(insn.popMemAddr()).toUnsignedLong()) }
                is Node.Instr.I64Load16S -> next { push(ctx.mem.getShort(insn.popMemAddr()).toLong()) }
                is Node.Instr.I64Load16U -> next { push(ctx.mem.getShort(insn.popMemAddr()).toUnsignedLong()) }
                is Node.Instr.I64Load32S -> next { push(ctx.mem.getInt(insn.popMemAddr()).toLong()) }
                is Node.Instr.I64Load32U -> next { push(ctx.mem.getInt(insn.popMemAddr()).toUnsignedLong()) }
                is Node.Instr.I32Store -> next { popInt().let { ctx.mem.putInt(insn.popMemAddr(), it) } }
                is Node.Instr.I64Store -> next { popLong().let { ctx.mem.putLong(insn.popMemAddr(), it) } }
                is Node.Instr.F32Store -> next { popFloat().let { ctx.mem.putFloat(insn.popMemAddr(), it) } }
                is Node.Instr.F64Store -> next {  popDouble().let { ctx.mem.putDouble(insn.popMemAddr(), it) } }
                is Node.Instr.I32Store8 -> next { popInt().let { ctx.mem.put(insn.popMemAddr(), it.toByte()) } }
                is Node.Instr.I32Store16 -> next { popInt().let { ctx.mem.putShort(insn.popMemAddr(), it.toShort()) } }
                is Node.Instr.I64Store8 -> next { popLong().let { ctx.mem.put(insn.popMemAddr(), it.toByte()) } }
                is Node.Instr.I64Store16 -> next { popLong().let { ctx.mem.putShort(insn.popMemAddr(), it.toShort()) } }
                is Node.Instr.I64Store32 -> next { popLong().let { ctx.mem.putInt(insn.popMemAddr(), it.toInt()) } }
                is Node.Instr.MemorySize -> next { push(ctx.mem.limit() / Mem.PAGE_SIZE) }
                is Node.Instr.MemoryGrow -> next {
                    val newLim = popInt() * Mem.PAGE_SIZE
                    if (newLim > ctx.mem.capacity()) push(-1)
                    else (ctx.mem.limit() / Mem.PAGE_SIZE).also { ctx.mem.limit(newLim) }
                }
                is Node.Instr.I32Const -> next { push(insn.value) }
                is Node.Instr.I64Const -> next { push(insn.value) }
                is Node.Instr.F32Const -> next { push(insn.value) }
                is Node.Instr.F64Const -> next { push(insn.value) }
                is Node.Instr.I32Eqz -> next { push(popInt() == 0) }
                is Node.Instr.I32Eq -> next { push(popInt() == popInt()) }
                is Node.Instr.I32Ne -> next { push(popInt() != popInt()) }
                is Node.Instr.I32LtS -> next { push(popInt() < popInt()) }
                is Node.Instr.I32LtU -> next { push(Integer.compareUnsigned(popInt(), popInt()) < 0) }
                is Node.Instr.I32GtS -> next { push(popInt() > popInt()) }
                is Node.Instr.I32GtU -> next { push(Integer.compareUnsigned(popInt(), popInt()) > 0) }
                is Node.Instr.I32LeS -> next { push(popInt() <= popInt()) }
                is Node.Instr.I32LeU -> next { push(Integer.compareUnsigned(popInt(), popInt()) <= 0) }
                is Node.Instr.I32GeS -> next { push(popInt() >= popInt()) }
                is Node.Instr.I32GeU -> next { push(Integer.compareUnsigned(popInt(), popInt()) >= 0) }
                is Node.Instr.I64Eqz -> next { push(popLong() == 0L) }
                is Node.Instr.I64Eq -> next { push(popLong() == popLong()) }
                is Node.Instr.I64Ne -> next { push(popLong() != popLong()) }
                is Node.Instr.I64LtS -> next { push(popLong() < popLong()) }
                is Node.Instr.I64LtU -> next { push(java.lang.Long.compareUnsigned(popLong(), popLong()) < 0) }
                is Node.Instr.I64GtS -> next { push(popLong() > popLong()) }
                is Node.Instr.I64GtU -> next { push(java.lang.Long.compareUnsigned(popLong(), popLong()) > 0) }
                is Node.Instr.I64LeS -> next { push(popLong() <= popLong()) }
                is Node.Instr.I64LeU -> next { push(java.lang.Long.compareUnsigned(popLong(), popLong()) <= 0) }
                is Node.Instr.I64GeS -> next { push(popLong() >= popLong()) }
                is Node.Instr.I64GeU -> next { push(java.lang.Long.compareUnsigned(popLong(), popLong()) >= 0) }
                is Node.Instr.F32Eq -> next { push(popFloat() == popFloat()) }
                is Node.Instr.F32Ne -> next { push(popFloat() != popFloat()) }
                is Node.Instr.F32Lt -> next { push(popFloat() < popFloat()) }
                is Node.Instr.F32Gt -> next { push(popFloat() > popFloat()) }
                is Node.Instr.F32Le -> next { push(popFloat() <= popFloat()) }
                is Node.Instr.F32Ge -> next { push(popFloat() >= popFloat()) }
                is Node.Instr.F64Eq -> next { push(popDouble() == popDouble()) }
                is Node.Instr.F64Ne -> next { push(popDouble() != popDouble()) }
                is Node.Instr.F64Lt -> next { push(popDouble() < popDouble()) }
                is Node.Instr.F64Gt -> next { push(popDouble() > popDouble()) }
                is Node.Instr.F64Le -> next { push(popDouble() <= popDouble()) }
                is Node.Instr.F64Ge -> next { push(popDouble() >= popDouble()) }
                else -> TODO()
            }
        }
    }

    data class Context(
        val mod: Node.Module,
        val callStack: MutableList<FuncContext> = mutableListOf(),
        var mem: ByteBuffer
    ) {
        init {
            require(mem.order() == ByteOrder.LITTLE_ENDIAN)
        }

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
        fun popLong() = pop() as Long
        fun popFloat() = pop() as Float
        fun popDouble() = pop() as Double
        fun Node.Instr.Args.AlignOffset.popMemAddr() = popInt() + offset.toInt()
        fun push(v: Number) { valueStack += v }
        fun push(v: Boolean) { valueStack += if (v) 1 else 0 }

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