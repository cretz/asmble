package asmble.run.jvm.interpret

import asmble.ast.Node
import asmble.compile.jvm.*
import asmble.util.Either
import asmble.util.toUnsignedInt
import asmble.util.toUnsignedLong
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

// This is not intended to be fast, rather clear and easy to read. Very little cached/memoized, lots of extra cycles.
open class Interpreter {

    fun execFunc(
        ctx: Context,
        funcIndex: Int = ctx.mod.startFuncIndex ?: error("No start func index"),
        funcArgs: List<Number> = emptyList()
    ): Number? {
        // Make first call, checking params
        var lastStep: StepResult = ctx.funcTypeAtIndex(funcIndex).let { funcType ->
            funcArgs.mapNotNull { it.valueType }.let {
                if (it != funcType.params) throw InterpretErr.StartFuncParamMismatch(funcType.params, it)
            }
            StepResult.Call(funcIndex, funcArgs, funcType)
        }
        // Run until done
        while (lastStep !is StepResult.Return || ctx.callStack.size > 1) {
            next(ctx, lastStep)
            lastStep = step(ctx)
        }
        return lastStep.v
    }

    fun step(ctx: Context): StepResult = ctx.currFuncCtx.run {
        // If the insn is out of bounds, it's an implicit return, otherwise just execute the insn
        if (insnIndex >= func.instructions.size) StepResult.Return(func.type.ret?.let { pop(it) })
        else invokeSingle(ctx)
    }

    // Errors with InterpretErr.EndReached if there is no next step to be had
    fun next(ctx: Context, step: StepResult) {
        when (step) {
            // Next just moves the counter
            is StepResult.Next -> ctx.currFuncCtx.insnIndex++
            // TODO: branch
            is StepResult.Branch -> TODO()
            // Call, if import, invokes it and puts result on stack. If not, just pushes a new func context.
            is StepResult.Call -> ctx.funcAtIndex(step.funcIndex).let {
                when (it) {
                    // If import, call and just put on stack and advance insn counter
                    is Either.Left ->
                        ctx.imports.invokeFunction(it.v.module, it.v.field, step.type, step.args).also {
                            // Make sure result type is accurate
                            if (it.valueType != step.type.ret)
                                throw InterpretErr.InvalidCallResult(step.type.ret, it)
                            it?.also { ctx.currFuncCtx.push(it) }
                            ctx.currFuncCtx.insnIndex++
                        }
                    // If inside the module, create new context to continue
                    is Either.Right -> ctx.callStack += FuncContext(it.v)
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
                step.v?.also { ctx.currFuncCtx.valueStack += it }
                ctx.currFuncCtx.insnIndex++
            }
        }
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
                is Node.Instr.BrTable -> StepResult.Branch(insn.targetTable.getOrNull(popInt())
                    ?: insn.default)
                is Node.Instr.Return ->
                    StepResult.Return(func.type.ret?.let { pop(it) })
                is Node.Instr.Call -> ctx.funcTypeAtIndex(insn.index).let {
                    StepResult.Call(insn.index, popCallArgs(it), it)
                }
                is Node.Instr.CallIndirect -> {
                    val tableIndex = popInt()
                    val expectedType = ctx.typeAtIndex(insn.index).also {
                        val tableMh = ctx.table?.getOrNull(tableIndex) ?: error("Missing table entry")
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
                is Node.Instr.I32Clz -> next { push(Integer.numberOfLeadingZeros(popInt())) }
                is Node.Instr.I32Ctz -> next { push(Integer.numberOfTrailingZeros(popInt())) }
                is Node.Instr.I32Popcnt -> next { push(Integer.bitCount(popInt())) }
                is Node.Instr.I32Add -> next { push(popInt() + popInt()) }
                is Node.Instr.I32Sub -> next { push(popInt() - popInt()) }
                is Node.Instr.I32Mul -> next { push(popInt() * popInt()) }
                is Node.Instr.I32DivS -> next { push(popInt() / popInt()) }
                is Node.Instr.I32DivU -> next { push(Integer.divideUnsigned(popInt(), popInt())) }
                is Node.Instr.I32RemS -> next { push(popInt() % popInt()) }
                is Node.Instr.I32RemU -> next { push(Integer.remainderUnsigned(popInt(), popInt())) }
                is Node.Instr.I32And -> next { push(popInt() and popInt()) }
                is Node.Instr.I32Or -> next { push(popInt() or popInt()) }
                is Node.Instr.I32Xor -> next { push(popInt() xor popInt()) }
                is Node.Instr.I32Shl -> next { push(popInt() shl popInt()) }
                is Node.Instr.I32ShrS -> next { push(popInt() shr popInt()) }
                is Node.Instr.I32ShrU -> next { push(popInt() ushr popInt()) }
                is Node.Instr.I32Rotl -> next { push(Integer.rotateLeft(popInt(), popInt())) }
                is Node.Instr.I32Rotr -> next { push(Integer.rotateRight(popInt(), popInt())) }
                is Node.Instr.I64Clz -> next { push(java.lang.Long.numberOfLeadingZeros(popLong()).toLong()) }
                is Node.Instr.I64Ctz -> next { push(java.lang.Long.numberOfTrailingZeros(popLong()).toLong()) }
                is Node.Instr.I64Popcnt -> next { push(java.lang.Long.bitCount(popLong())) }
                is Node.Instr.I64Add -> next { push(popLong() + popLong()) }
                is Node.Instr.I64Sub -> next { push(popLong() - popLong()) }
                is Node.Instr.I64Mul -> next { push(popLong() * popLong()) }
                is Node.Instr.I64DivS -> next { push(popLong() / popLong()) }
                is Node.Instr.I64DivU -> next { push(java.lang.Long.divideUnsigned(popLong(), popLong())) }
                is Node.Instr.I64RemS -> next { push(popLong() % popLong()) }
                is Node.Instr.I64RemU -> next { push(java.lang.Long.remainderUnsigned(popLong(), popLong())) }
                is Node.Instr.I64And -> next { push(popLong() and popLong()) }
                is Node.Instr.I64Or -> next { push(popLong() or popLong()) }
                is Node.Instr.I64Xor -> next { push(popLong() xor popLong()) }
                is Node.Instr.I64Shl -> next { push(popLong() shl popInt()) }
                is Node.Instr.I64ShrS -> next { push(popLong() shr popInt()) }
                is Node.Instr.I64ShrU -> next { push(popLong() ushr popInt()) }
                is Node.Instr.I64Rotl -> next { push(java.lang.Long.rotateLeft(popLong(), popInt())) }
                is Node.Instr.I64Rotr -> next { push(java.lang.Long.rotateRight(popLong(), popInt())) }
                is Node.Instr.F32Abs -> next { push(Math.abs(popFloat())) }
                is Node.Instr.F32Neg -> next { push(-popFloat()) }
                is Node.Instr.F32Ceil -> next { push(Math.ceil(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Floor -> next { push(Math.floor(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Trunc -> next {
                    popFloat().toDouble().let { push((if (it >= 0.0) Math.floor(it) else Math.ceil(it)).toFloat()) }
                }
                is Node.Instr.F32Nearest -> next { push(Math.rint(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Sqrt -> next { push(Math.sqrt(popFloat().toDouble()).toFloat()) }
                is Node.Instr.F32Add -> next { push(popFloat() + popFloat()) }
                is Node.Instr.F32Sub -> next { push(popFloat() - popFloat()) }
                is Node.Instr.F32Mul -> next { push(popFloat() * popFloat()) }
                is Node.Instr.F32Div -> next { push(popFloat() / popFloat()) }
                is Node.Instr.F32Min -> next { Math.min(popFloat(), popFloat()) }
                is Node.Instr.F32Max -> next { Math.max(popFloat(), popFloat()) }
                is Node.Instr.F32CopySign -> next { Math.copySign(popFloat(), popFloat()) }
                is Node.Instr.F64Abs -> next { push(Math.abs(popDouble())) }
                is Node.Instr.F64Neg -> next { push(-popDouble()) }
                is Node.Instr.F64Ceil -> next { push(Math.ceil(popDouble())) }
                is Node.Instr.F64Floor -> next { push(Math.floor(popDouble())) }
                is Node.Instr.F64Trunc -> next {
                    popDouble().let { push((if (it >= 0.0) Math.floor(it) else Math.ceil(it))) }
                }
                is Node.Instr.F64Nearest -> next { push(Math.rint(popDouble())) }
                is Node.Instr.F64Sqrt -> next { push(Math.sqrt(popDouble())) }
                is Node.Instr.F64Add -> next { push(popDouble() + popDouble()) }
                is Node.Instr.F64Sub -> next { push(popDouble() - popDouble()) }
                is Node.Instr.F64Mul -> next { push(popDouble() * popDouble()) }
                is Node.Instr.F64Div -> next { push(popDouble() / popDouble()) }
                is Node.Instr.F64Min -> next { Math.min(popDouble(), popDouble()) }
                is Node.Instr.F64Max -> next { Math.max(popDouble(), popDouble()) }
                is Node.Instr.F64CopySign -> next { Math.copySign(popDouble(), popDouble()) }
                is Node.Instr.I32WrapI64 -> next { push(popLong().toInt()) }
                // TODO: trunc traps on overflow!
                is Node.Instr.I32TruncSF32 -> next { push(popFloat().toInt()) }
                is Node.Instr.I32TruncUF32 -> next { push(popFloat().toLong().toInt()) }
                is Node.Instr.I32TruncSF64 -> next { push(popDouble().toInt()) }
                is Node.Instr.I32TruncUF64 -> next { push(popDouble().toLong().toInt()) }
                is Node.Instr.I64ExtendSI32 -> next { push(popInt().toLong()) }
                is Node.Instr.I64ExtendUI32 -> next { push(popInt().toUnsignedLong()) }
                is Node.Instr.I64TruncSF32 -> next { push(popFloat().toLong()) }
                is Node.Instr.I64TruncUF32 -> next {
                    // If over max long, subtract and negate
                    popFloat().let {
                        push(
                            if (it < 9223372036854775807f) it.toLong()
                            else (-9223372036854775808f + (it - 9223372036854775807f)).toLong()
                        )
                    }
                }
                is Node.Instr.I64TruncSF64 -> next { push(popDouble().toLong()) }
                is Node.Instr.I64TruncUF64 -> next {
                    // If over max long, subtract and negate
                    popDouble().let {
                        push(
                            if (it < 9223372036854775807.0) it.toLong()
                            else (-9223372036854775808.0 + (it - 9223372036854775807.0)).toLong()
                        )
                    }
                }
                is Node.Instr.F32ConvertSI32 -> next { push(popInt().toFloat()) }
                is Node.Instr.F32ConvertUI32 -> next { push(popInt().toUnsignedLong().toFloat()) }
                is Node.Instr.F32ConvertSI64 -> next { push(popLong().toFloat()) }
                is Node.Instr.F32ConvertUI64 -> next {
                    popLong().let { if (it >= 0) it.toFloat() else (it ushr 1).toFloat() * 2f }
                }
                is Node.Instr.F32DemoteF64 -> next { push(popDouble().toFloat()) }
                is Node.Instr.F64ConvertSI32 -> next { push(popInt().toDouble()) }
                is Node.Instr.F64ConvertUI32 -> next { push(popInt().toUnsignedLong().toDouble()) }
                is Node.Instr.F64ConvertSI64 -> next { push(popLong().toDouble()) }
                is Node.Instr.F64ConvertUI64 -> next {
                    popLong().let { if (it >= 0) it.toDouble() else ((it ushr 1) or (it and 1)) * 2.0 }
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
        val imports: Imports = Imports.None,
        val defaultMaxMemPages: Int = 1,
        val memByteBufferDirect: Boolean = true
    ) {
        val callStack = mutableListOf<FuncContext>()
        val currFuncCtx get() = callStack.last()

        val exportsByName = mod.exports.map { it.field to it }.toMap()
        fun exportIndex(field: String, kind: Node.ExternalKind) =
            exportsByName[field]?.takeIf { it.kind == kind }?.index

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
        fun boundFuncMethodHandleAtIndex(index: Int): MethodHandle = TODO()

        val importGlobals = mod.imports.filter { it.kind is Node.Import.Kind.Global }
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
                val arr = importTable?.let { imports.getTable(it.module, it.field, table).copyOf() } ?:
                    arrayOfNulls(table.limits.initial)
                // Now put all the elements in there
                mod.elems.forEach { elem ->
                    require(elem.index == 0)
                    // Offset index always a constant or import
                    val offsetVal = singleConstant(elem.offset) as? Int ?: throw CompileErr.OffsetNotConstant()
                    elem.funcIndices.forEachIndexed { index, funcIndex ->
                        arr[offsetVal + index] = boundFuncMethodHandleAtIndex(funcIndex)
                    }
                }
                arr
            }
        }
    }

    data class FuncContext(
        val func: Node.Func,
        val locals: MutableList<Number> = mutableListOf(),
        val valueStack: MutableList<Number> = mutableListOf(),
        val blockStack: MutableList<Block> = mutableListOf(),
        var insnIndex: Int = 0
    ) {
        fun peek() = valueStack.last()
        fun pop() = valueStack.removeAt(valueStack.size - 1)
        fun popInt() = pop() as Int
        fun popLong() = pop() as Long
        fun popFloat() = pop() as Float
        fun popDouble() = pop() as Double
        fun Node.Instr.Args.AlignOffset.popMemAddr() = popInt() + offset.toInt()
        fun pop(type: Node.Type.Value): Number = when (type) {
            is Node.Type.Value.I32 -> popInt()
            is Node.Type.Value.I64 -> popLong()
            is Node.Type.Value.F32 -> popFloat()
            is Node.Type.Value.F64 -> popDouble()
        }
        fun popCallArgs(type: Node.Type.Func) = type.params.map(::pop)
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