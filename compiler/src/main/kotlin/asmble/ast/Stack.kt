package asmble.ast

data class Stack(
    // If some of these values below are null, the pops/pushes may appear "unknown"
    val mod: CachedModule? = null,
    val func: Node.Func? = null,
    // Null if not tracking the current stack and all pops succeed
    val current: List<Node.Type.Value>? = null,
    val insnApplies: List<InsnApply> = emptyList(),
    val strictPop: Boolean = false
) {

    fun next(v: Node.Instr, callFuncType: Node.Type.Func? = null) = insnApply(v) {
        when (v) {
            is Node.Instr.Unreachable, is Node.Instr.Nop, is Node.Instr.Block,
            is Node.Instr.Loop, is Node.Instr.If, is Node.Instr.Else,
            is Node.Instr.End, is Node.Instr.Br, is Node.Instr.BrIf,
            is Node.Instr.Return -> nop()
            is Node.Instr.BrTable -> popI32()
            is Node.Instr.Call -> (callFuncType ?: error("Call func type missing")).let {
                it.params.reversed().flatMap { pop(it) } + (it.ret?.let { push(it) } ?: nop())
            }
            is Node.Instr.CallIndirect -> (callFuncType ?: error("Call func type missing")).let {
                // We add one for the table index
                popI32() + it.params.reversed().flatMap { pop(it) } + (it.ret?.let { push(it) } ?: nop())
            }
            is Node.Instr.Drop -> pop()
            is Node.Instr.Select -> popI32() + pop().let { it + pop(it.first().type) + push(it.first().type) }
            is Node.Instr.GetLocal -> push(local(v.index))
            is Node.Instr.SetLocal -> pop(local(v.index))
            is Node.Instr.TeeLocal -> local(v.index).let { pop(it) + push(it) }
            is Node.Instr.GetGlobal -> push(global(v.index))
            is Node.Instr.SetGlobal -> pop(global(v.index))
            is Node.Instr.I32Load, is Node.Instr.I32Load8S, is Node.Instr.I32Load8U,
                is Node.Instr.I32Load16U, is Node.Instr.I32Load16S -> popI32() + pushI32()
            is Node.Instr.I64Load, is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U,
                is Node.Instr.I64Load16S, is Node.Instr.I64Load32S, is Node.Instr.I64Load32U -> popI32() + pushI64()
            is Node.Instr.F32Load -> popI32() + popF32()
            is Node.Instr.F64Load -> popI32() + pushF64()
            is Node.Instr.I32Store, is Node.Instr.I32Store8, is Node.Instr.I32Store16 -> popI32() + popI32()
            is Node.Instr.I64Store, is Node.Instr.I64Store8,
                is Node.Instr.I64Store16, is Node.Instr.I64Store32 -> popI32() + popI64()
            is Node.Instr.F32Store -> popI32() + pushF32()
            is Node.Instr.F64Store -> popI32() + pushF64()
            is Node.Instr.MemorySize -> pushI32()
            is Node.Instr.MemoryGrow -> popI32() + pushI32()
            is Node.Instr.I32Const -> pushI32()
            is Node.Instr.I64Const -> pushI64()
            is Node.Instr.F32Const -> pushF32()
            is Node.Instr.F64Const -> pushF64()
            is Node.Instr.I32Add, is Node.Instr.I32Sub, is Node.Instr.I32Mul, is Node.Instr.I32DivS,
                is Node.Instr.I32DivU, is Node.Instr.I32RemS, is Node.Instr.I32RemU, is Node.Instr.I32And,
                is Node.Instr.I32Or, is Node.Instr.I32Xor, is Node.Instr.I32Shl, is Node.Instr.I32ShrS,
                is Node.Instr.I32ShrU, is Node.Instr.I32Rotl, is Node.Instr.I32Rotr, is Node.Instr.I32Eq,
                is Node.Instr.I32Ne, is Node.Instr.I32LtS, is Node.Instr.I32LeS, is Node.Instr.I32LtU,
                is Node.Instr.I32LeU, is Node.Instr.I32GtS, is Node.Instr.I32GeS, is Node.Instr.I32GtU,
                is Node.Instr.I32GeU -> popI32() + popI32() + pushI32()
            is Node.Instr.I32Clz, is Node.Instr.I32Ctz, is Node.Instr.I32Popcnt,
                is Node.Instr.I32Eqz -> popI32() + pushI32()
            is Node.Instr.I64Add, is Node.Instr.I64Sub, is Node.Instr.I64Mul, is Node.Instr.I64DivS,
                is Node.Instr.I64DivU, is Node.Instr.I64RemS, is Node.Instr.I64RemU, is Node.Instr.I64And,
                is Node.Instr.I64Or, is Node.Instr.I64Xor, is Node.Instr.I64Shl, is Node.Instr.I64ShrS,
                is Node.Instr.I64ShrU, is Node.Instr.I64Rotl, is Node.Instr.I64Rotr, is Node.Instr.I64Eq,
                is Node.Instr.I64Ne, is Node.Instr.I64LtS, is Node.Instr.I64LeS, is Node.Instr.I64LtU,
                is Node.Instr.I64LeU, is Node.Instr.I64GtS, is Node.Instr.I64GeS, is Node.Instr.I64GtU,
                is Node.Instr.I64GeU -> popI64() + popI64() + pushI64()
            is Node.Instr.I64Clz, is Node.Instr.I64Ctz, is Node.Instr.I64Popcnt,
                is Node.Instr.I64Eqz -> popI64() + pushI64()
            is Node.Instr.F32Add, is Node.Instr.F32Sub, is Node.Instr.F32Mul, is Node.Instr.F32Div,
                is Node.Instr.F32Eq, is Node.Instr.F32Ne, is Node.Instr.F32Lt, is Node.Instr.F32Le,
                is Node.Instr.F32Gt, is Node.Instr.F32Ge, is Node.Instr.F32Min,
                is Node.Instr.F32Max, is Node.Instr.F32CopySign -> popF32() + popF32() + pushF32()
            is Node.Instr.F32Abs, is Node.Instr.F32Neg, is Node.Instr.F32Ceil, is Node.Instr.F32Floor,
                is Node.Instr.F32Trunc, is Node.Instr.F32Nearest, is Node.Instr.F32Sqrt -> popF32() + pushF32()
            is Node.Instr.F64Add, is Node.Instr.F64Sub, is Node.Instr.F64Mul, is Node.Instr.F64Div,
                is Node.Instr.F64Eq, is Node.Instr.F64Ne, is Node.Instr.F64Lt, is Node.Instr.F64Le,
                is Node.Instr.F64Gt, is Node.Instr.F64Ge, is Node.Instr.F64Min,
                is Node.Instr.F64Max, is Node.Instr.F64CopySign -> popF64() + popF64() + pushF64()
            is Node.Instr.F64Abs, is Node.Instr.F64Neg, is Node.Instr.F64Ceil, is Node.Instr.F64Floor,
                is Node.Instr.F64Trunc, is Node.Instr.F64Nearest, is Node.Instr.F64Sqrt -> popF64() + popF64()
            is Node.Instr.I32WrapI64 -> popI32() + pushI64()
            is Node.Instr.I32TruncSF32, is Node.Instr.I32TruncUF32,
                is Node.Instr.I32ReinterpretF32 -> popI32() + pushF32()
            is Node.Instr.I32TruncSF64, is Node.Instr.I32TruncUF64 -> popI32() + pushF64()
            is Node.Instr.I64ExtendSI32, is Node.Instr.I64ExtendUI32 -> popI64() + pushI32()
            is Node.Instr.I64TruncSF32, is Node.Instr.I64TruncUF32 -> popI64() + pushF32()
            is Node.Instr.I64TruncSF64, is Node.Instr.I64TruncUF64,
                is Node.Instr.I64ReinterpretF64 -> popI64() + pushF64()
            is Node.Instr.F32ConvertSI32, is Node.Instr.F32ConvertUI32,
                is Node.Instr.F32ReinterpretI32 -> popF32() + pushI32()
            is Node.Instr.F32ConvertSI64, is Node.Instr.F32ConvertUI64,
                is Node.Instr.F64ReinterpretI64 -> popF32() + pushI64()
            is Node.Instr.F32DemoteF64 -> popF32() + pushF64()
            is Node.Instr.F64ConvertSI32, is Node.Instr.F64ConvertUI32 -> popF64() + pushI32()
            is Node.Instr.F64ConvertSI64, is Node.Instr.F64ConvertUI64 -> popF64() + pushI64()
            is Node.Instr.F64PromoteF32 -> popF64() + pushF32()
        }
    }

    protected fun insnApply(v: Node.Instr, fn: MutableList<Node.Type.Value>?.() -> List<StackChange>): Stack {
        val mutStack = current?.toMutableList()
        val stackChanges = mutStack.fn()
        return copy(
            current = mutStack,
            insnApplies = insnApplies + InsnApply(
                insn = v,
                stackAtBeginning = current,
                stackChanges = stackChanges
            )
        )
    }

    protected fun local(index: Int) = func?.let {
        it.type.params.getOrNull(index) ?: it.locals.getOrNull(index - it.type.params.size)
    }
    protected fun global(index: Int) = mod?.let {
        it.importGlobals.getOrNull(index)?.type?.contentType ?:
        it.mod.globals.getOrNull(index - it.importGlobals.size)?.type?.contentType
    }
    protected fun func(index: Int) = mod?.let {
        it.importFuncs.getOrNull(index)?.typeIndex?.let { i -> it.mod.types.getOrNull(i) } ?:
            it.mod.funcs.getOrNull(index - it.importFuncs.size)?.type
    }

    protected fun MutableList<Node.Type.Value>?.nop() = emptyList<StackChange>()
    protected fun MutableList<Node.Type.Value>?.popType(expecting: Node.Type.Value? = null) =
        this?.takeIf {
            it.isNotEmpty().also { require(!strictPop || it) }
        }?.let {
            removeAt(size - 1).takeIf { (it == expecting).also { require(!strictPop || it) } }
        } ?: expecting
    protected fun MutableList<Node.Type.Value>?.pop(expecting: Node.Type.Value? = null) =
        listOf(StackChange(popType(expecting), true))

    protected fun MutableList<Node.Type.Value>?.popI32() = pop(Node.Type.Value.I32)
    protected fun MutableList<Node.Type.Value>?.popI64() = pop(Node.Type.Value.I64)
    protected fun MutableList<Node.Type.Value>?.popF32() = pop(Node.Type.Value.F32)
    protected fun MutableList<Node.Type.Value>?.popF64() = pop(Node.Type.Value.F64)

    protected fun push(type: Node.Type.Value? = null) = listOf(StackChange(type, false))
    protected fun pushI32() = push(Node.Type.Value.I32)
    protected fun pushI64() = push(Node.Type.Value.I64)
    protected fun pushF32() = push(Node.Type.Value.F32)
    protected fun pushF64() = push(Node.Type.Value.F64)

    data class InsnApply(
        val insn: Node.Instr,
        val stackAtBeginning: List<Node.Type.Value>?,
        val stackChanges: List<StackChange>
    )

    data class StackChange(
        val type: Node.Type.Value?,
        val pop: Boolean
    )

    class CachedModule(val mod: Node.Module) {
        val importFuncs by lazy { mod.imports.mapNotNull { it.kind as? Node.Import.Kind.Func } }
        val importGlobals by lazy { mod.imports.mapNotNull { it.kind as? Node.Import.Kind.Global } }
    }

    companion object {
        fun stackChanges(v: Node.Instr, callFuncType: Node.Type.Func? = null) =
            Stack().next(v, callFuncType).insnApplies.last().stackChanges
        fun stackDiff(v: Node.Instr, callFuncType: Node.Type.Func? = null) =
            stackChanges(v, callFuncType).sumBy { if (it.pop) -1 else 1 }
    }
}