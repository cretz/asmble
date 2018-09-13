package asmble.compile.jvm

import asmble.ast.Node

open class InsnReworker {

    fun rework(ctx: ClsContext, func: Node.Func): List<Insn> {
        return injectNeededStackVars(ctx, func.instructions).let { insns ->
            addEagerLocalInitializers(ctx, func, insns)
        }
    }

    fun addEagerLocalInitializers(ctx: ClsContext, func: Node.Func, insns: List<Insn>): List<Insn> {
        if (func.locals.isEmpty()) return insns
        // The JVM requires you set a local before you access it. WASM requires that
        // all locals are implicitly zero. After some thought, we're going to make this
        // an easy algorithm where, for any get_local, there must be a set/tee_local
        // in a preceding insn before a branch of any form (br, br_if, and br_table).
        // If there isn't, an eager set_local will be added at the beginning to init
        // to 0.
        //
        // This should prevent any false positives (i.e. a get_local before
        // a set/tee_local) while reducing false negatives (i.e. a get_local where it
        // just doesn't seem like there is a set/tee but there is). Sure there are more
        // accurate ways such as specifically injecting sets where needed, or turning
        // the first non-set get to a tee, or counting specific block depths, but this
        // keeps it simple for now.
        //
        // Note, while walking backwards up the insns to find set/tee, we do skip entire
        // blocks/loops/if+else combined with "end"
        var neededEagerLocalIndices = emptySet<Int>()
        fun addEagerSetIfNeeded(getInsnIndex: Int, localIndex: Int) {
            // Within the param range? nothing needed
            if (localIndex < func.type.params.size) return
            // Already loading? nothing needed
            if (neededEagerLocalIndices.contains(localIndex)) return
            var blockInitsToSkip = 0
            // Get first set/tee or branching insn (or nothing of course)
            val insn = insns.take(getInsnIndex).asReversed().find { insn ->
                insn is Insn.Node && when (insn.insn) {
                    // End means we need to skip to next block start
                    is Node.Instr.End -> {
                        blockInitsToSkip++
                        false
                    }
                    // Else with no inits to skip means we are in the else
                    // and we should skip to the if (i.e. nothing between
                    // if and else)
                    is Node.Instr.Else -> {
                        if (blockInitsToSkip == 0) blockInitsToSkip++
                        false
                    }
                    // Block init, decrement skip count
                    is Node.Instr.Block, is Node.Instr.Loop, is Node.Instr.If -> {
                        if (blockInitsToSkip > 0) blockInitsToSkip--
                        false
                    }
                    // Branch means we found it if we're not skipping
                    is Node.Instr.Br, is Node.Instr.BrIf, is Node.Instr.BrTable ->
                        blockInitsToSkip == 0
                    // Set/Tee means we found it if the index is right
                    // and we're not skipping
                    is Node.Instr.SetLocal, is Node.Instr.TeeLocal ->
                        blockInitsToSkip == 0 && (insn.insn as Node.Instr.Args.Index).index == localIndex
                    // Anything else doesn't matter
                    else -> false
                }
            }
            // If the insn is not set or tee, we have to eager init
            val needsEagerInit = insn == null ||
                (insn is Insn.Node && insn.insn !is Node.Instr.SetLocal && insn.insn !is Node.Instr.TeeLocal)
            if (needsEagerInit) neededEagerLocalIndices += localIndex
        }
        insns.forEachIndexed { index, insn ->
            if (insn is Insn.Node && insn.insn is Node.Instr.GetLocal) addEagerSetIfNeeded(index, insn.insn.index)
        }
        // Now, in local order, prepend needed local inits
        return neededEagerLocalIndices.sorted().flatMap {
            val const: Node.Instr = when (func.localByIndex(it)) {
                is Node.Type.Value.I32 -> Node.Instr.I32Const(0)
                is Node.Type.Value.I64 -> Node.Instr.I64Const(0)
                is Node.Type.Value.F32 -> Node.Instr.F32Const(0f)
                is Node.Type.Value.F64 -> Node.Instr.F64Const(0.0)
            }
            listOf(Insn.Node(const), Insn.Node(Node.Instr.SetLocal(it)))
        } + insns
    }

    fun injectNeededStackVars(ctx: ClsContext, insns: List<Node.Instr>): List<Insn> {
        ctx.trace { "Calculating places to inject needed stack variables" }
        // How we do this:
        // We run over each insn, and keep a running list of stack
        // manips. If there is an insn that needs something so far back,
        // we calc where it needs to be added and keep a running list of
        // insn inserts. Then at the end we settle up.
        //
        // Note, we don't do any injections for things like "this" if
        // they aren't needed up the stack (e.g. a simple getfield can
        // just aload 0 itself). Also we take special care not to inject
        // inside of an inner block.

        // Each pair is first the amount of stack that is changed (0 is
        // ignored, push is positive, pull is negative) then the index
        // of the insn that caused it. As a special case, if the stack
        // is dynamic (i.e. call_indirect
        var stackManips = emptyList<Pair<Int, Int>>()

        // Keyed by the index to inject. With how the algorithm works, we
        // guarantee the value will be in the right order if there are
        // multiple for the same index
        var insnsToInject = emptyMap<Int, List<Insn>>()
        fun injectBeforeLastStackCount(insn: Insn, count: Int) {
            ctx.trace { "Injecting $insn back $count stack values" }
            fun inject(index: Int) {
                insnsToInject += index to (insnsToInject[index]?.let { listOf(insn) + it } ?: listOf(insn))
            }
            if (count == 0) return inject(stackManips.size)
            var countSoFar = 0
            var foundUnconditionalJump = false
            var insideOfBlocks = 0
            for ((amountChanged, insnIndex) in stackManips.asReversed()) {
                // We have to skip inner blocks because we don't want to inject inside of there
                if (insns[insnIndex] == Node.Instr.End) {
                    insideOfBlocks++
                    ctx.trace { "Found end, not injecting until before $insideOfBlocks more block start(s)" }
                    continue
                }

                // When we reach the top of a block, we need to decrement out inside count and
                // if we are at 0, add the result of said block if necessary to the count.
                if (insideOfBlocks > 0) {
                    // If it's not a block, just ignore it
                    (insns[insnIndex] as? Node.Instr.Args.Type)?.let {
                        insideOfBlocks--
                        ctx.trace { "Found block begin, number of blocks we're still inside: $insideOfBlocks" }
                        // We're back on our block, change the count if it had a result
                        if (insideOfBlocks == 0 && it.type != null) countSoFar++
                    }
                    if (insideOfBlocks > 0) continue
                }

                countSoFar += amountChanged
                if (!foundUnconditionalJump) foundUnconditionalJump = insns[insnIndex].let { insn ->
                    insn is Node.Instr.Br || insn is Node.Instr.BrTable ||
                        insn is Node.Instr.Unreachable || insn is Node.Instr.Return
                }
                if (countSoFar == count) {
                    ctx.trace { "Found injection point as before insn #$insnIndex" }
                    return inject(insnIndex)
                }
            }
            // Only consider it a failure if we didn't hit any unconditional jumps
            if (!foundUnconditionalJump) throw CompileErr.StackInjectionMismatch(count, insn)
        }

        var traceStackSize = 0 // Used only for trace
        // Go over each insn, determining where to inject
        insns.forEachIndexed { index, insn ->
            // Handle special injection cases
            when (insn) {
                // Calls require "this" or fn ref before the params
                is Node.Instr.Call -> {
                    val inject =
                        if (insn.index < ctx.importFuncs.size) Insn.ImportFuncRefNeededOnStack(insn.index)
                        else Insn.ThisNeededOnStack
                    injectBeforeLastStackCount(inject, ctx.funcTypeAtIndex(insn.index).params.size)
                }
                // Indirect calls require "this" before the index
                is Node.Instr.CallIndirect ->
                    injectBeforeLastStackCount(Insn.ThisNeededOnStack, 1)
                // Global set requires "this" before the single param
                is Node.Instr.SetGlobal -> {
                    val inject =
                        if (insn.index < ctx.importGlobals.size) Insn.ImportGlobalSetRefNeededOnStack(insn.index)
                        else Insn.ThisNeededOnStack
                    injectBeforeLastStackCount(inject, 1)
                }
                // Loads require "mem" before the single param
                is Node.Instr.I32Load, is Node.Instr.I64Load, is Node.Instr.F32Load, is Node.Instr.F64Load,
                is Node.Instr.I32Load8S, is Node.Instr.I32Load8U, is Node.Instr.I32Load16U, is Node.Instr.I32Load16S,
                is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U, is Node.Instr.I64Load16S,
                is Node.Instr.I64Load32S, is Node.Instr.I64Load32U ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 1)
                // Storage requires "mem" before the single param
                is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
                is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
                is Node.Instr.I64Store32 ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 2)
                // Grow memory requires "mem" before the single param
                is Node.Instr.MemoryGrow ->
                    injectBeforeLastStackCount(Insn.MemNeededOnStack, 1)
                else -> { }
            }

            // Log some trace output
            ctx.trace {
                insnStackDiff(ctx, insn).let {
                    traceStackSize += it
                    "Stack diff is $it for insn #$index $insn, stack size now: $traceStackSize"
                }
            }

            // Add the current diff
            stackManips += insnStackDiff(ctx, insn) to index
        }

        // Build resulting list
        return insns.foldIndexed(emptyList<Insn>()) { index, ret, insn ->
            val injections = insnsToInject[index] ?: emptyList()
            ret + injections + Insn.Node(insn)
        }
    }

    fun insnStackDiff(ctx: ClsContext, insn: Node.Instr) = when (insn) {
        is Node.Instr.Unreachable, is Node.Instr.Nop, is Node.Instr.Block,
        is Node.Instr.Loop, is Node.Instr.Else, is Node.Instr.End, is Node.Instr.Br,
        is Node.Instr.Return -> NOP
        is Node.Instr.If, is Node.Instr.BrIf, is Node.Instr.BrTable -> POP_PARAM
        is Node.Instr.Call -> ctx.funcTypeAtIndex(insn.index).let {
            // All calls pop params and any return is a push
            (POP_PARAM * it.params.size) + (if (it.ret == null) NOP else PUSH_RESULT)
        }
        is Node.Instr.CallIndirect -> ctx.typeAtIndex(insn.index).let {
            // We add one for the table index
            POP_PARAM + (POP_PARAM * it.params.size) + (if (it.ret == null) NOP else PUSH_RESULT)
        }
        is Node.Instr.Drop -> POP_PARAM
        is Node.Instr.Select -> (POP_PARAM * 3) + PUSH_RESULT
        is Node.Instr.GetLocal -> PUSH_RESULT
        is Node.Instr.SetLocal -> POP_PARAM
        is Node.Instr.TeeLocal -> POP_PARAM + PUSH_RESULT
        is Node.Instr.GetGlobal -> PUSH_RESULT
        is Node.Instr.SetGlobal -> POP_PARAM
        is Node.Instr.I32Load, is Node.Instr.I64Load, is Node.Instr.F32Load, is Node.Instr.F64Load,
        is Node.Instr.I32Load8S, is Node.Instr.I32Load8U, is Node.Instr.I32Load16U, is Node.Instr.I32Load16S,
        is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U, is Node.Instr.I64Load16S,
        is Node.Instr.I64Load32S, is Node.Instr.I64Load32U -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
        is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
        is Node.Instr.I64Store32 -> POP_PARAM + POP_PARAM
        is Node.Instr.MemorySize -> PUSH_RESULT
        is Node.Instr.MemoryGrow -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I32Const, is Node.Instr.I64Const,
        is Node.Instr.F32Const, is Node.Instr.F64Const -> PUSH_RESULT
        is Node.Instr.I32Add, is Node.Instr.I32Sub, is Node.Instr.I32Mul, is Node.Instr.I32DivS,
        is Node.Instr.I32DivU, is Node.Instr.I32RemS, is Node.Instr.I32RemU, is Node.Instr.I32And,
        is Node.Instr.I32Or, is Node.Instr.I32Xor, is Node.Instr.I32Shl, is Node.Instr.I32ShrS,
        is Node.Instr.I32ShrU, is Node.Instr.I32Rotl, is Node.Instr.I32Rotr, is Node.Instr.I32Eq,
        is Node.Instr.I32Ne, is Node.Instr.I32LtS, is Node.Instr.I32LeS, is Node.Instr.I32LtU,
        is Node.Instr.I32LeU, is Node.Instr.I32GtS, is Node.Instr.I32GeS, is Node.Instr.I32GtU,
        is Node.Instr.I32GeU -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.I32Clz, is Node.Instr.I32Ctz, is Node.Instr.I32Popcnt,
        is Node.Instr.I32Eqz -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I64Add, is Node.Instr.I64Sub, is Node.Instr.I64Mul, is Node.Instr.I64DivS,
        is Node.Instr.I64DivU, is Node.Instr.I64RemS, is Node.Instr.I64RemU, is Node.Instr.I64And,
        is Node.Instr.I64Or, is Node.Instr.I64Xor, is Node.Instr.I64Shl, is Node.Instr.I64ShrS,
        is Node.Instr.I64ShrU, is Node.Instr.I64Rotl, is Node.Instr.I64Rotr, is Node.Instr.I64Eq,
        is Node.Instr.I64Ne, is Node.Instr.I64LtS, is Node.Instr.I64LeS, is Node.Instr.I64LtU,
        is Node.Instr.I64LeU, is Node.Instr.I64GtS, is Node.Instr.I64GeS, is Node.Instr.I64GtU,
        is Node.Instr.I64GeU -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.I64Clz, is Node.Instr.I64Ctz, is Node.Instr.I64Popcnt,
        is Node.Instr.I64Eqz -> POP_PARAM + PUSH_RESULT
        is Node.Instr.F32Add, is Node.Instr.F32Sub, is Node.Instr.F32Mul, is Node.Instr.F32Div,
        is Node.Instr.F32Eq, is Node.Instr.F32Ne, is Node.Instr.F32Lt, is Node.Instr.F32Le,
        is Node.Instr.F32Gt, is Node.Instr.F32Ge, is Node.Instr.F32Min,
        is Node.Instr.F32Max, is Node.Instr.F32CopySign -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.F32Abs, is Node.Instr.F32Neg, is Node.Instr.F32Ceil, is Node.Instr.F32Floor,
        is Node.Instr.F32Trunc, is Node.Instr.F32Nearest, is Node.Instr.F32Sqrt -> POP_PARAM + PUSH_RESULT
        is Node.Instr.F64Add, is Node.Instr.F64Sub, is Node.Instr.F64Mul, is Node.Instr.F64Div,
        is Node.Instr.F64Eq, is Node.Instr.F64Ne, is Node.Instr.F64Lt, is Node.Instr.F64Le,
        is Node.Instr.F64Gt, is Node.Instr.F64Ge, is Node.Instr.F64Min,
        is Node.Instr.F64Max, is Node.Instr.F64CopySign -> POP_PARAM + POP_PARAM + PUSH_RESULT
        is Node.Instr.F64Abs, is Node.Instr.F64Neg, is Node.Instr.F64Ceil, is Node.Instr.F64Floor,
        is Node.Instr.F64Trunc, is Node.Instr.F64Nearest, is Node.Instr.F64Sqrt -> POP_PARAM + PUSH_RESULT
        is Node.Instr.I32WrapI64, is Node.Instr.I32TruncSF32, is Node.Instr.I32TruncUF32,
        is Node.Instr.I32TruncSF64, is Node.Instr.I32TruncUF64, is Node.Instr.I64ExtendSI32,
        is Node.Instr.I64ExtendUI32, is Node.Instr.I64TruncSF32, is Node.Instr.I64TruncUF32,
        is Node.Instr.I64TruncSF64, is Node.Instr.I64TruncUF64, is Node.Instr.F32ConvertSI32,
        is Node.Instr.F32ConvertUI32, is Node.Instr.F32ConvertSI64, is Node.Instr.F32ConvertUI64,
        is Node.Instr.F32DemoteF64, is Node.Instr.F64ConvertSI32, is Node.Instr.F64ConvertUI32,
        is Node.Instr.F64ConvertSI64, is Node.Instr.F64ConvertUI64, is Node.Instr.F64PromoteF32,
        is Node.Instr.I32ReinterpretF32, is Node.Instr.I64ReinterpretF64, is Node.Instr.F32ReinterpretI32,
        is Node.Instr.F64ReinterpretI64 -> POP_PARAM + PUSH_RESULT
    }

    fun nonAdjacentMemAccesses(insns: List<Insn>) = insns.fold(0 to false) { (count, lastCouldHaveMem), insn ->
        val inc =
            if (lastCouldHaveMem) 0
            else if (insn == Insn.MemNeededOnStack) 1
            else if (insn is Insn.Node && insn.insn is Node.Instr.MemorySize) 1
            else 0
        val couldSetMemNext = if (insn !is Insn.Node) false else when (insn.insn) {
            is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
            is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
            is Node.Instr.I64Store32, is Node.Instr.MemoryGrow -> true
            else -> false
        }
        (count + inc) to couldSetMemNext
    }.let { (count, _) -> count }

    companion object : InsnReworker() {
        const val POP_PARAM = -1
        const val PUSH_RESULT = 1
        const val NOP = 0
    }
}