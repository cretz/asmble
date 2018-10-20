package asmble.run.jvm.interpret

import asmble.ast.Node
import asmble.compile.jvm.*
import asmble.run.jvm.RunErr
import asmble.util.Either
import asmble.util.Logger
import asmble.util.toUnsignedInt
import asmble.util.toUnsignedLong
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.ByteBuffer
import java.nio.ByteOrder

// This is not intended to be fast, rather clear and easy to read. Very little cached/memoized, lots of extra cycles.
open class Interpreter {

    open fun execFunc(
        ctx: Context,
        funcIndex: Int = ctx.mod.startFuncIndex ?: error("No start func index"),
        vararg funcArgs: Number
    ): Number? {
        // Check params
        val funcType = ctx.funcTypeAtIndex(funcIndex)
        funcArgs.mapNotNull { it.valueType }.let {
            if (it != funcType.params) throw InterpretErr.StartFuncParamMismatch(funcType.params, it)
        }
        // Import functions are executed inline and returned
        ctx.importFuncs.getOrNull(funcIndex)?.also {
            return ctx.imports.invokeFunction(it.module, it.field, funcType, funcArgs.toList())
        }
        // This is the call stack we need to stop at
        val startingCallStackSize = ctx.callStack.size
        // Make the call on the context
        var lastStep: StepResult = StepResult.Call(funcIndex, funcArgs.toList(), funcType)
        // Run until done
        while (lastStep !is StepResult.Return || ctx.callStack.size > startingCallStackSize + 1) {
            next(ctx, lastStep)
            lastStep = step(ctx)
        }
        ctx.callStack.subList(startingCallStackSize, ctx.callStack.size).clear()
        return lastStep.v
    }

    open fun step(ctx: Context): StepResult = ctx.currFuncCtx.run {
        // If the insn is out of bounds, it's an implicit return, otherwise just execute the insn
        if (insnIndex >= func.instructions.size) StepResult.Return(func.type.ret?.let { pop(it) })
        else invokeSingle(ctx)
    }

    // Errors with InterpretErr.EndReached if there is no next step to be had
    open fun next(ctx: Context, step: StepResult) {
        ctx.logger.trace {
            val fnName = ctx.maybeCurrFuncCtx?.funcIndex?.let { ctx.mod.names?.funcNames?.get(it) ?: it.toString() }
            "NEXT ON $fnName(${ctx.maybeCurrFuncCtx?.funcIndex}:${ctx.maybeCurrFuncCtx?.insnIndex}): $step " +
                "[VAL STACK: ${ctx.maybeCurrFuncCtx?.valueStack}] " +
                "[CALL STACK DEPTH: ${ctx.callStack.size}]"
        }
        when (step) {
            // Next just moves the counter
            is StepResult.Next -> ctx.currFuncCtx.insnIndex++
            // Branch updates the stack and moves the insn index
            is StepResult.Branch -> ctx.currFuncCtx.run {
                // A failed if just jumps to the else or end
                if (step.failedIf) {
                    require(step.blockDepth == 0)
                    val block = blockStack.last()
                    if (block.elseIndex != null) insnIndex = block.elseIndex + 1
                    else {
                        insnIndex = block.endIndex + 1
                        blockStack.removeAt(blockStack.size - 1)
                    }
                } else {
                    // Remove all blocks until the depth requested
                    blockStack.subList(blockStack.size - step.blockDepth, blockStack.size).clear()
                    // This can break out of the entire function
                    if (blockStack.isEmpty()) {
                        // Grab the stack item if present, blow away stack, put back, and move to end of func
                        val retVal = func.type.ret?.let { pop(it) }
                        valueStack.clear()
                        retVal?.also { push(it) }
                        insnIndex = func.instructions.size
                    } else if (blockStack.last().insn is Node.Instr.Loop && !step.forceEndOnLoop) {
                        // It's just a loop continuation, go back to top
                        insnIndex = blockStack.last().startIndex + 1
                    } else {
                        // Remove the one at the depth requested
                        val block = blockStack.removeAt(blockStack.size - 1)
                        // Pop value if applicable
                        val blockVal = block.insn.type?.let { pop(it) }
                        // Trim the stack down to required size
                        valueStack.subList(block.stackSizeAtStart, valueStack.size).clear()
                        // Put the value back on if applicable
                        blockVal?.also { push(it) }
                        // Jump past the end
                        insnIndex = block.endIndex + 1
                    }
                }
            }
            // Call, if import, invokes it and puts result on stack. If not, just pushes a new func context.
            is StepResult.Call -> ctx.funcAtIndex(step.funcIndex).let {
                when (it) {
                    // If import, call and just put on stack and advance insn if came from insn
                    is Either.Left ->
                        ctx.imports.invokeFunction(it.v.module, it.v.field, step.type, step.args).also {
                            // Make sure result type is accurate
                            if (it.valueType != step.type.ret)
                                throw InterpretErr.InvalidCallResult(step.type.ret, it)
                            it?.also { ctx.currFuncCtx.push(it) }
                            ctx.currFuncCtx.insnIndex++
                        }
                    // If inside the module, create new context to continue
                    is Either.Right -> ctx.callStack += FuncContext(step.funcIndex, it.v).also { funcCtx ->
                        ctx.logger.debug {
                            ">".repeat(ctx.callStack.size) + " " + ctx.mod.names?.funcNames?.get(funcCtx.funcIndex) +
                                ":${funcCtx.funcIndex} - args: " + step.args
                        }
                        // Set the args
                        step.args.forEachIndexed { index, arg -> funcCtx.locals[index] = arg  }
                    }
                }
            }
            // Call indirect is just an MH invocation
            is StepResult.CallIndirect -> {
                val mh = ctx.table?.getOrNull(step.tableIndex) ?: error("Missing table entry")
                val res = mh.invokeWithArguments(step.args) as? Number?
                if (res.valueType != step.type.ret) throw InterpretErr.InvalidCallResult(step.type.ret, res)
                res?.also { ctx.currFuncCtx.push(it) }
                ctx.currFuncCtx.insnIndex++
            }
            // Unreachable throws
            is StepResult.Unreachable -> throw UnsupportedOperationException("Unreachable")
            // Return pops curr func from the call stack, push ret and move insn on prev one
            is StepResult.Return -> ctx.callStack.removeAt(ctx.callStack.lastIndex).let { returnedFrom ->
                if (ctx.callStack.isEmpty()) throw InterpretErr.EndReached(step.v)
                if (returnedFrom.valueStack.isNotEmpty())
                    throw CompileErr.UnusedStackOnReturn(returnedFrom.valueStack.map { it::class.ref } )
                step.v?.also { ctx.currFuncCtx.push(it) }
                ctx.currFuncCtx.insnIndex++
            }
        }
    }

    open fun invokeSingle(ctx: Context): StepResult = ctx.currFuncCtx.run {
        // TODO: validation?
        func.instructions[insnIndex].let { insn ->
            ctx.logger.trace { "INSN #$insnIndex: $insn [STACK: $valueStack]" }
            when (insn) {
                is Node.Instr.Unreachable -> StepResult.Unreachable
                is Node.Instr.Nop -> next { }
                is Node.Instr.Block, is Node.Instr.Loop -> next {
                    blockStack += Block(insnIndex, insn as Node.Instr.Args.Type, valueStack.size, currentBlockEnd()!!)
                }
                is Node.Instr.If -> {
                    blockStack += Block(insnIndex, insn, valueStack.size - 1, currentBlockEnd()!!, currentBlockElse())
                    if (popInt() == 0) StepResult.Branch(0, failedIf = true) else StepResult.Next
                }
                is Node.Instr.Else ->
                    // Jump over the whole thing and to the end, this can only be gotten here via if
                    StepResult.Branch(0)
                is Node.Instr.End ->
                    // Since we reached the end by manually running through it, jump to end even on loop
                    StepResult.Branch(0, forceEndOnLoop = true)
                is Node.Instr.Br -> StepResult.Branch(insn.relativeDepth)
                is Node.Instr.BrIf -> if (popInt() != 0) StepResult.Branch(insn.relativeDepth) else StepResult.Next
                is Node.Instr.BrTable -> StepResult.Branch(insn.targetTable.getOrNull(popInt())
                    ?: insn.default)
                is Node.Instr.Return ->
                    StepResult.Return(func.type.ret?.let { pop(it) })
                is Node.Instr.Call -> ctx.funcTypeAtIndex(insn.index).let {
                    ctx.checkNextIsntStackOverflow()
                    StepResult.Call(insn.index, popCallArgs(it), it)
                }
                is Node.Instr.CallIndirect -> {
                    ctx.checkNextIsntStackOverflow()
                    val tableIndex = popInt()
                    val expectedType = ctx.typeAtIndex(insn.index).also {
                        val tableMh = ctx.table?.getOrNull(tableIndex) ?:
                            throw InterpretErr.UndefinedElement(tableIndex)
                        val actualType = Node.Type.Func(
                            params = tableMh.type().parameterList().map { it.valueType!! },
                            ret = tableMh.type().returnType().valueType
                        )
                        if (it != actualType) throw InterpretErr.IndirectCallTypeMismatch(it, actualType)
                    }
                    StepResult.CallIndirect(tableIndex, popCallArgs(expectedType), expectedType)
                }
                is Node.Instr.Drop -> next { pop() }
                is Node.Instr.Select -> next {
                    popInt().also {
                        val v2 = pop()
                        val v1 = pop()
                        if (v1::class != v2::class)
                            throw CompileErr.SelectMismatch(v1.valueType!!.typeRef, v2.valueType!!.typeRef)
                        if (it != 0) push(v1) else push(v2)
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
                    val newLim = ctx.mem.limit().toLong() + (popInt().toLong() * Mem.PAGE_SIZE)
                    if (newLim > ctx.mem.capacity()) push(-1)
                    else (ctx.mem.limit() / Mem.PAGE_SIZE).also {
                        push(it)
                        ctx.mem.limit(newLim.toInt())
                    }
                }
                is Node.Instr.I32Const -> next { push(insn.value) }
                is Node.Instr.I64Const -> next { push(insn.value) }
                is Node.Instr.F32Const -> next { push(insn.value) }
                is Node.Instr.F64Const -> next { push(insn.value) }
                is Node.Instr.I32Eqz -> next { push(popInt() == 0) }
                is Node.Instr.I32Eq -> nextBinOp(popInt(), popInt()) { a, b -> a == b }
                is Node.Instr.I32Ne -> nextBinOp(popInt(), popInt()) { a, b -> a != b }
                is Node.Instr.I32LtS -> nextBinOp(popInt(), popInt()) { a, b -> a < b }
                is Node.Instr.I32LtU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.compareUnsigned(a, b) < 0 }
                is Node.Instr.I32GtS -> nextBinOp(popInt(), popInt()) { a, b -> a > b }
                is Node.Instr.I32GtU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.compareUnsigned(a, b) > 0 }
                is Node.Instr.I32LeS -> nextBinOp(popInt(), popInt()) { a, b -> a <= b }
                is Node.Instr.I32LeU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.compareUnsigned(a, b) <= 0 }
                is Node.Instr.I32GeS -> nextBinOp(popInt(), popInt()) { a, b -> a >= b }
                is Node.Instr.I32GeU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.compareUnsigned(a, b) >= 0 }
                is Node.Instr.I64Eqz -> next { push(popLong() == 0L) }
                is Node.Instr.I64Eq -> nextBinOp(popLong(), popLong()) { a, b -> a == b }
                is Node.Instr.I64Ne -> nextBinOp(popLong(), popLong()) { a, b -> a != b }
                is Node.Instr.I64LtS -> nextBinOp(popLong(), popLong()) { a, b -> a < b }
                is Node.Instr.I64LtU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.compareUnsigned(a, b) < 0 }
                is Node.Instr.I64GtS -> nextBinOp(popLong(), popLong()) { a, b -> a > b }
                is Node.Instr.I64GtU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.compareUnsigned(a, b) > 0 }
                is Node.Instr.I64LeS -> nextBinOp(popLong(), popLong()) { a, b -> a <= b }
                is Node.Instr.I64LeU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.compareUnsigned(a, b) <= 0 }
                is Node.Instr.I64GeS -> nextBinOp(popLong(), popLong()) { a, b -> a >= b }
                is Node.Instr.I64GeU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.compareUnsigned(a, b) >= 0 }
                is Node.Instr.F32Eq -> nextBinOp(popFloat(), popFloat()) { a, b -> a == b }
                is Node.Instr.F32Ne -> nextBinOp(popFloat(), popFloat()) { a, b -> a != b }
                is Node.Instr.F32Lt -> nextBinOp(popFloat(), popFloat()) { a, b -> a < b }
                is Node.Instr.F32Gt -> nextBinOp(popFloat(), popFloat()) { a, b -> a > b }
                is Node.Instr.F32Le -> nextBinOp(popFloat(), popFloat()) { a, b -> a <= b }
                is Node.Instr.F32Ge -> nextBinOp(popFloat(), popFloat()) { a, b -> a >= b }
                is Node.Instr.F64Eq -> nextBinOp(popDouble(), popDouble()) { a, b -> a == b }
                is Node.Instr.F64Ne -> nextBinOp(popDouble(), popDouble()) { a, b -> a != b }
                is Node.Instr.F64Lt -> nextBinOp(popDouble(), popDouble()) { a, b -> a < b }
                is Node.Instr.F64Gt -> nextBinOp(popDouble(), popDouble()) { a, b -> a > b }
                is Node.Instr.F64Le -> nextBinOp(popDouble(), popDouble()) { a, b -> a <= b }
                is Node.Instr.F64Ge -> nextBinOp(popDouble(), popDouble()) { a, b -> a >= b }
                is Node.Instr.I32Clz -> next { push(Integer.numberOfLeadingZeros(popInt())) }
                is Node.Instr.I32Ctz -> next { push(Integer.numberOfTrailingZeros(popInt())) }
                is Node.Instr.I32Popcnt -> next { push(Integer.bitCount(popInt())) }
                is Node.Instr.I32Add -> nextBinOp(popInt(), popInt()) { a, b -> a + b }
                is Node.Instr.I32Sub -> nextBinOp(popInt(), popInt()) { a, b -> a - b }
                is Node.Instr.I32Mul -> nextBinOp(popInt(), popInt()) { a, b -> a * b }
                is Node.Instr.I32DivS -> nextBinOp(popInt(), popInt()) { a, b ->
                    ctx.checkedSignedDivInteger(a, b)
                    a / b
                }
                is Node.Instr.I32DivU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.divideUnsigned(a, b) }
                is Node.Instr.I32RemS -> nextBinOp(popInt(), popInt()) { a, b -> a % b }
                is Node.Instr.I32RemU -> nextBinOp(popInt(), popInt()) { a, b -> Integer.remainderUnsigned(a, b) }
                is Node.Instr.I32And -> nextBinOp(popInt(), popInt()) { a, b -> a and b }
                is Node.Instr.I32Or -> nextBinOp(popInt(), popInt()) { a, b -> a or b }
                is Node.Instr.I32Xor -> nextBinOp(popInt(), popInt()) { a, b -> a xor b }
                is Node.Instr.I32Shl -> nextBinOp(popInt(), popInt()) { a, b -> a shl b }
                is Node.Instr.I32ShrS -> nextBinOp(popInt(), popInt()) { a, b -> a shr b }
                is Node.Instr.I32ShrU -> nextBinOp(popInt(), popInt()) { a, b -> a ushr b }
                is Node.Instr.I32Rotl -> nextBinOp(popInt(), popInt()) { a, b -> Integer.rotateLeft(a, b) }
                is Node.Instr.I32Rotr -> nextBinOp(popInt(), popInt()) { a, b -> Integer.rotateRight(a, b) }
                is Node.Instr.I64Clz -> next { push(java.lang.Long.numberOfLeadingZeros(popLong()).toLong()) }
                is Node.Instr.I64Ctz -> next { push(java.lang.Long.numberOfTrailingZeros(popLong()).toLong()) }
                is Node.Instr.I64Popcnt -> next { push(java.lang.Long.bitCount(popLong()).toLong()) }
                is Node.Instr.I64Add -> nextBinOp(popLong(), popLong()) { a, b -> a + b }
                is Node.Instr.I64Sub -> nextBinOp(popLong(), popLong()) { a, b -> a - b }
                is Node.Instr.I64Mul -> nextBinOp(popLong(), popLong()) { a, b -> a * b }
                is Node.Instr.I64DivS -> nextBinOp(popLong(), popLong()) { a, b ->
                    ctx.checkedSignedDivInteger(a, b)
                    a / b
                }
                is Node.Instr.I64DivU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.divideUnsigned(a, b) }
                is Node.Instr.I64RemS -> nextBinOp(popLong(), popLong()) { a, b -> a % b }
                is Node.Instr.I64RemU -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.remainderUnsigned(a, b) }
                is Node.Instr.I64And -> nextBinOp(popLong(), popLong()) { a, b -> a and b }
                is Node.Instr.I64Or -> nextBinOp(popLong(), popLong()) { a, b -> a or b }
                is Node.Instr.I64Xor -> nextBinOp(popLong(), popLong()) { a, b -> a xor b }
                is Node.Instr.I64Shl -> nextBinOp(popLong(), popLong()) { a, b -> a shl b.toInt() }
                is Node.Instr.I64ShrS -> nextBinOp(popLong(), popLong()) { a, b -> a shr b.toInt() }
                is Node.Instr.I64ShrU -> nextBinOp(popLong(), popLong()) { a, b -> a ushr b.toInt() }
                is Node.Instr.I64Rotl -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.rotateLeft(a, b.toInt()) }
                is Node.Instr.I64Rotr -> nextBinOp(popLong(), popLong()) { a, b -> java.lang.Long.rotateRight(a, b.toInt()) }
                is Node.Instr.F32Abs -> next { push(Math.abs(popFloat())) }
                is Node.Instr.F32Neg -> next { push(-popFloat()) }
                is Node.Instr.F32Ceil -> next { push(Math.ceil(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Floor -> next { push(Math.floor(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Trunc -> next {
                    popFloat().toDouble().let { push((if (it >= 0.0) Math.floor(it) else Math.ceil(it)).toFloat()) }
                }
                is Node.Instr.F32Nearest -> next { push(Math.rint(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Sqrt -> next { push(Math.sqrt(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Add -> nextBinOp(popFloat(), popFloat()) { a, b -> a + b }
                is Node.Instr.F32Sub -> nextBinOp(popFloat(), popFloat()) { a, b -> a - b }
                is Node.Instr.F32Mul -> nextBinOp(popFloat(), popFloat()) { a, b -> a * b }
                is Node.Instr.F32Div -> nextBinOp(popFloat(), popFloat()) { a, b -> a / b }
                is Node.Instr.F32Min -> nextBinOp(popFloat(), popFloat()) { a, b -> Math.min(a, b) }
                is Node.Instr.F32Max -> nextBinOp(popFloat(), popFloat()) { a, b -> Math.max(a, b) }
                is Node.Instr.F32CopySign -> nextBinOp(popFloat(), popFloat()) { a, b -> Math.copySign(a, b) }
                is Node.Instr.F64Abs -> next { push(Math.abs(popDouble())) }
                is Node.Instr.F64Neg -> next { push(-popDouble()) }
                is Node.Instr.F64Ceil -> next { push(Math.ceil(popDouble())) }
                is Node.Instr.F64Floor -> next { push(Math.floor(popDouble())) }
                is Node.Instr.F64Trunc -> next {
                    popDouble().let { push((if (it >= 0.0) Math.floor(it) else Math.ceil(it))) }
                }
                is Node.Instr.F64Nearest -> next { push(Math.rint(popDouble())) }
                is Node.Instr.F64Sqrt -> next { push(Math.sqrt(popDouble())) }
                is Node.Instr.F64Add -> nextBinOp(popDouble(), popDouble()) { a, b -> a + b }
                is Node.Instr.F64Sub -> nextBinOp(popDouble(), popDouble()) { a, b -> a - b }
                is Node.Instr.F64Mul -> nextBinOp(popDouble(), popDouble()) { a, b -> a * b }
                is Node.Instr.F64Div -> nextBinOp(popDouble(), popDouble()) { a, b -> a / b }
                is Node.Instr.F64Min -> nextBinOp(popDouble(), popDouble()) { a, b -> Math.min(a, b) }
                is Node.Instr.F64Max -> nextBinOp(popDouble(), popDouble()) { a, b -> Math.max(a, b) }
                is Node.Instr.F64CopySign -> nextBinOp(popDouble(), popDouble()) { a, b -> Math.copySign(a, b) }
                is Node.Instr.I32WrapI64 -> next { push(popLong().toInt()) }
                // TODO: trunc traps on overflow!
                is Node.Instr.I32TruncSF32 -> next {
                    push(ctx.checkedTrunc(popFloat(), true) { it.toInt() })
                }
                is Node.Instr.I32TruncUF32 -> next {
                    push(ctx.checkedTrunc(popFloat(), false) { it.toLong().toInt() })
                }
                is Node.Instr.I32TruncSF64 -> next {
                    push(ctx.checkedTrunc(popDouble(), true) { it.toInt() })
                }
                is Node.Instr.I32TruncUF64 -> next {
                    push(ctx.checkedTrunc(popDouble(), false) { it.toLong().toInt() })
                }
                is Node.Instr.I64ExtendSI32 -> next { push(popInt().toLong()) }
                is Node.Instr.I64ExtendUI32 -> next { push(popInt().toUnsignedLong()) }
                is Node.Instr.I64TruncSF32 -> next {
                    push(ctx.checkedTrunc(popFloat(), true) { it.toLong() })
                }
                is Node.Instr.I64TruncUF32 -> next {
                    push(ctx.checkedTrunc(popFloat(), false) {
                        // If over max long, subtract and negate
                        if (it < 9223372036854775807f) it.toLong()
                        else (-9223372036854775808f + (it - 9223372036854775807f)).toLong()
                    })
                }
                is Node.Instr.I64TruncSF64 -> next {
                    push(ctx.checkedTrunc(popDouble(), true) { it.toLong() })
                }
                is Node.Instr.I64TruncUF64 -> next {
                    push(ctx.checkedTrunc(popDouble(), false) {
                        // If over max long, subtract and negate
                        if (it < 9223372036854775807.0) it.toLong()
                        else (-9223372036854775808.0 + (it - 9223372036854775807.0)).toLong()
                    })
                }
                is Node.Instr.F32ConvertSI32 -> next { push(popInt().toFloat()) }
                is Node.Instr.F32ConvertUI32 -> next { push(popInt().toUnsignedLong().toFloat()) }
                is Node.Instr.F32ConvertSI64 -> next { push(popLong().toFloat()) }
                is Node.Instr.F32ConvertUI64 -> next {
                    push(popLong().let { if (it >= 0) it.toFloat() else (it ushr 1).toFloat() * 2f })
                }
                is Node.Instr.F32DemoteF64 -> next { push(popDouble().toFloat()) }
                is Node.Instr.F64ConvertSI32 -> next { push(popInt().toDouble()) }
                is Node.Instr.F64ConvertUI32 -> next { push(popInt().toUnsignedLong().toDouble()) }
                is Node.Instr.F64ConvertSI64 -> next { push(popLong().toDouble()) }
                is Node.Instr.F64ConvertUI64 -> next {
                    push(popLong().let { if (it >= 0) it.toDouble() else ((it ushr 1) or (it and 1)) * 2.0 })
                }
                is Node.Instr.F64PromoteF32 -> next { push(popFloat().toDouble()) }
                is Node.Instr.I32ReinterpretF32 -> next { push(java.lang.Float.floatToRawIntBits(popFloat())) }
                is Node.Instr.I64ReinterpretF64 -> next { push(java.lang.Double.doubleToRawLongBits(popDouble())) }
                is Node.Instr.F32ReinterpretI32 -> next { push(java.lang.Float.intBitsToFloat(popInt())) }
                is Node.Instr.F64ReinterpretI64 -> next { push(java.lang.Double.longBitsToDouble(popLong())) }
            }
        }
    }

    companion object : Interpreter()

    // Creating this does all the initialization except execute the start function
    data class Context(
        val mod: Node.Module,
        val logger: Logger = Logger.Print(Logger.Level.OFF),
        val interpreter: Interpreter = Interpreter.Companion,
        val imports: Imports = Imports.None,
        val defaultMaxMemPages: Int = 1,
        val memByteBufferDirect: Boolean = true,
        val checkTruncOverflow: Boolean = true,
        val checkSignedDivIntegerOverflow: Boolean = true,
        val maximumCallStackDepth: Int = 3000
    ) {
        val callStack = mutableListOf<FuncContext>()
        val currFuncCtx get() = callStack.last()
        val maybeCurrFuncCtx get() = callStack.lastOrNull()

        val exportsByName = mod.exports.map { it.field to it }.toMap()
        fun exportIndex(field: String, kind: Node.ExternalKind) =
            exportsByName[field]?.takeIf { it.kind == kind }?.index

        val importGlobals = mod.imports.filter { it.kind is Node.Import.Kind.Global }
        fun singleConstant(instrs: List<Node.Instr>): Number? = instrs.singleOrNull().let { instr ->
            when (instr) {
                is Node.Instr.Args.Const<*> -> instr.value
                is Node.Instr.GetGlobal -> importGlobals.getOrNull(instr.index).let {
                    it ?: throw CompileErr.UnknownGlobal(instr.index)
                    imports.getGlobal(it.module, it.field, (it.kind as Node.Import.Kind.Global).type)
                }
                else -> null
            }
        }

        val maybeMem = run {
            // Import it if we can, otherwise make it
            val memImport = mod.imports.singleOrNull { it.kind is Node.Import.Kind.Memory }
            // TODO: validate imported memory
            val mem =
                if (memImport != null) imports.getMemory(
                    memImport.module,
                    memImport.field,
                    (memImport.kind as Node.Import.Kind.Memory).type
                ) else mod.memories.singleOrNull()?.let { memType ->
                    val max = (memType.limits.maximum ?: defaultMaxMemPages) * Mem.PAGE_SIZE
                    val mem = if (memByteBufferDirect) ByteBuffer.allocateDirect(max) else ByteBuffer.allocate(max)
                    mem.apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                        limit(memType.limits.initial * Mem.PAGE_SIZE)
                    }
                }
            mem?.also { mem ->
                // Load all data
                mod.data.forEach { data ->
                    val pos = singleConstant(data.offset) as? Int ?: throw CompileErr.OffsetNotConstant()
                    if (pos < 0 || pos + data.data.size > mem.limit())
                        throw RunErr.InvalidDataIndex(pos, data.data.size, mem.limit())
                    mem.duplicate().apply { position(pos) }.put(data.data)
                }
            }
        }
        val mem get() = maybeMem ?: throw CompileErr.UnknownMemory(0)

        // TODO: some of this shares with the compiler's context, so how about some code reuse?
        val importFuncs = mod.imports.filter { it.kind is Node.Import.Kind.Func }
        fun typeAtIndex(index: Int) = mod.types.getOrNull(index) ?: throw CompileErr.UnknownType(index)
        fun funcAtIndex(index: Int) = importFuncs.getOrNull(index).let {
            when (it) {
                null -> Either.Right(mod.funcs.getOrNull(index - importFuncs.size) ?: throw CompileErr.UnknownFunc(index))
                else -> Either.Left(it)
            }
        }
        fun funcTypeAtIndex(index: Int) = funcAtIndex(index).let {
            when (it) {
                is Either.Left -> typeAtIndex((it.v.kind as Node.Import.Kind.Func).typeIndex)
                is Either.Right -> it.v.type
            }
        }
        fun boundFuncMethodHandleAtIndex(index: Int): MethodHandle {
            val type = funcTypeAtIndex(index).let {
                MethodType.methodType(it.ret?.jclass ?: Void.TYPE, it.params.map { it.jclass })
            }
            val origMh = MethodHandles.lookup().bind(interpreter, "execFunc", MethodType.methodType(
                Number::class.java, Context::class.java, Int::class.java, Array<Number>::class.java))
            return MethodHandles.insertArguments(origMh, 0, this, index).
                asVarargsCollector(Array<Number>::class.java).asType(type)
        }

        val moduleGlobals = mod.globals.mapIndexed { index, global ->
            // In MVP all globals have an init, it's either a const or an import read
            val initVal = singleConstant(global.init) ?: throw CompileErr.GlobalInitNotConstant(index)
            if (initVal.valueType != global.type.contentType)
                throw CompileErr.GlobalConstantMismatch(index, global.type.contentType.typeRef, initVal::class.ref)
            initVal
        }.toMutableList()
        fun globalTypeAtIndex(index: Int) =
            (importGlobals.getOrNull(index)?.kind as? Node.Import.Kind.Global)?.type ?:
                mod.globals[index - importGlobals.size].type
        fun getGlobal(index: Int): Number = importGlobals.getOrNull(index).let { importGlobal ->
            if (importGlobal != null) imports.getGlobal(
                importGlobal.module,
                importGlobal.field,
                (importGlobal.kind as Node.Import.Kind.Global).type
            ) else moduleGlobals.getOrNull(index - importGlobals.size) ?: error("No global")
        }
        fun setGlobal(index: Int, v: Number) {
            val importGlobal = importGlobals.getOrNull(index)
            if (importGlobal != null) imports.setGlobal(
                importGlobal.module,
                importGlobal.field,
                (importGlobal.kind as Node.Import.Kind.Global).type,
                v
            ) else (index - importGlobals.size).also { index ->
                require(index < moduleGlobals.size)
                moduleGlobals[index] = v
            }
        }

        val table = run {
            val importTable = mod.imports.singleOrNull { it.kind is Node.Import.Kind.Table }
            val table = (importTable?.kind as? Node.Import.Kind.Table)?.type ?: mod.tables.singleOrNull()
            if (table == null && mod.elems.isNotEmpty()) throw CompileErr.UnknownTable(0)
            table?.let { table ->
                // Create array either cloned from import or fresh
                val arr = importTable?.let { imports.getTable(it.module, it.field, table) } ?:
                    arrayOfNulls(table.limits.initial)
                // Now put all the elements in there
                mod.elems.forEach { elem ->
                    require(elem.index == 0)
                    // Offset index always a constant or import
                    val offsetVal = singleConstant(elem.offset) as? Int ?: throw CompileErr.OffsetNotConstant()
                    // Still have to validate offset even if no func indexes
                    if (offsetVal < 0 || offsetVal + elem.funcIndices.size > arr.size)
                        throw RunErr.InvalidElemIndex(offsetVal, elem.funcIndices.size, arr.size)
                    elem.funcIndices.forEachIndexed { index, funcIndex ->
                        arr[offsetVal + index] = boundFuncMethodHandleAtIndex(funcIndex)
                    }
                }
                arr
            }
        }

        fun <T : Number> checkedTrunc(orig: Float, signed: Boolean, to: (Float) -> T) = to(orig).also {
            if (checkTruncOverflow) {
                if (orig.isNaN()) throw InterpretErr.TruncIntegerNaN(orig, it.valueType!!, signed)
                val invalid =
                    (it is Int && signed && (orig < -2147483648f || orig >= 2147483648f)) ||
                    (it is Int && !signed && (orig.toInt() < 0 || orig >= 4294967296f)) ||
                    (it is Long && signed && (orig < -9223372036854775807f || orig >= 9223372036854775807f)) ||
                    (it is Long && !signed && (orig.toInt() < 0 || orig >= 18446744073709551616f))
                if (invalid) throw InterpretErr.TruncIntegerOverflow(orig, it.valueType!!, signed)
            }
        }

        fun <T : Number> checkedTrunc(orig: Double, signed: Boolean, to: (Double) -> T) = to(orig).also {
            if (checkTruncOverflow) {
                if (orig.isNaN()) throw InterpretErr.TruncIntegerNaN(orig, it.valueType!!, signed)
                val invalid =
                    (it is Int && signed && (orig < -2147483648.0 || orig >= 2147483648.0)) ||
                    (it is Int && !signed && (orig.toInt() < 0 || orig >= 4294967296.0)) ||
                    (it is Long && signed && (orig < -9223372036854775807.0 || orig >= 9223372036854775807.0)) ||
                    (it is Long && !signed && (orig.toInt() < 0 || orig >= 18446744073709551616.0))
                if (invalid) throw InterpretErr.TruncIntegerOverflow(orig, it.valueType!!, signed)
            }
        }

        fun checkedSignedDivInteger(a: Int, b: Int) {
            if (checkSignedDivIntegerOverflow && (a == Int.MIN_VALUE && b == -1))
                throw InterpretErr.SignedDivOverflow(a, b)
        }

        fun checkedSignedDivInteger(a: Long, b: Long) {
            if (checkSignedDivIntegerOverflow && (a == Long.MIN_VALUE && b == -1L))
                throw InterpretErr.SignedDivOverflow(a, b)
        }

        fun checkNextIsntStackOverflow() {
            // TODO: note this doesn't keep count of imports and their call stack
            if (callStack.size + 1 >= maximumCallStackDepth) {
                // We blow away the entire stack here so code can continue...could provide stack to
                // exception if we wanted
                callStack.clear()
                throw InterpretErr.StackOverflow(maximumCallStackDepth)
            }
        }
    }

    data class FuncContext(
        val funcIndex: Int,
        val func: Node.Func,
        val valueStack: MutableList<Number> = mutableListOf(),
        val blockStack: MutableList<Block> = mutableListOf(),
        var insnIndex: Int = 0
    ) {
        val locals = (func.type.params + func.locals).map {
            when (it) {
                is Node.Type.Value.I32 -> 0 as Number
                is Node.Type.Value.I64 -> 0L as Number
                is Node.Type.Value.F32 -> 0f as Number
                is Node.Type.Value.F64 -> 0.0 as Number
            }
        }.toMutableList()

        fun peek() = valueStack.last()
        fun pop() = valueStack.removeAt(valueStack.size - 1)
        fun popInt() = pop() as Int
        fun popLong() = pop() as Long
        fun popFloat() = pop() as Float
        fun popDouble() = pop() as Double
        fun Node.Instr.Args.AlignOffset.popMemAddr(): Int {
            val v = popInt()
            if (offset > Int.MAX_VALUE || offset + v > Int.MAX_VALUE) throw InterpretErr.OutOfBoundsMemory(v, offset)
            return v + offset.toInt()
        }
        fun pop(type: Node.Type.Value): Number = when (type) {
            is Node.Type.Value.I32 -> popInt()
            is Node.Type.Value.I64 -> popLong()
            is Node.Type.Value.F32 -> popFloat()
            is Node.Type.Value.F64 -> popDouble()
        }
        fun popCallArgs(type: Node.Type.Func) = type.params.reversed().map(::pop).reversed()
        fun push(v: Number) { valueStack += v }
        fun push(v: Boolean) { valueStack += if (v) 1 else 0 }

        fun currentBlockEndOrElse(end: Boolean): Int? {
            // Find the next end/else
            var blockDepth = 0
            val index = func.instructions.drop(insnIndex + 1).indexOfFirst { insn ->
                // Increase block depth if necessary
                when (insn) { is Node.Instr.Block, is Node.Instr.Loop, is Node.Instr.If -> blockDepth++ }
                // If we're at the end of ourself but not looking for end, short-circuit a failure
                if (blockDepth == 0 && !end && insn is Node.Instr.End) return null
                // Did we find an end or an else on ourself?
                val found = blockDepth == 0 && ((end && insn is Node.Instr.End) || (!end && insn is Node.Instr.Else))
                if (blockDepth > 0 && insn is Node.Instr.End) blockDepth--
                found
            }
            return if (index == -1) null else index + insnIndex + 1
        }
        fun currentBlockEnd() = currentBlockEndOrElse(true)
        fun currentBlockElse(): Int? = currentBlockEndOrElse(false)

        inline fun next(crossinline f: () -> Unit) = StepResult.Next.also { f() }
        inline fun <T, U> nextBinOp(second: T, first: U, crossinline f: (U, T) -> Any) = StepResult.Next.also {
            val v = f(first, second)
            if (v is Boolean) push(v) else push(v as Number)
        }
    }

    data class Block(
        val startIndex: Int,
        val insn: Node.Instr.Args.Type,
        val stackSizeAtStart: Int,
        val endIndex: Int,
        val elseIndex: Int? = null
    )

    sealed class StepResult {
        object Next : StepResult()
        data class Branch(
            val blockDepth: Int,
            val failedIf: Boolean = false,
            val forceEndOnLoop: Boolean = false
        ) : StepResult()
        data class Call(
            val funcIndex: Int,
            val args: List<Number>,
            val type: Node.Type.Func
        ) : StepResult()
        data class CallIndirect(
            val tableIndex: Int,
            val args: List<Number>,
            val type: Node.Type.Func
        ) : StepResult()
        object Unreachable : StepResult()
        data class Return(val v: Number?) : StepResult()
    }
}