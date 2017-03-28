package asmble.compile.jvm

import asmble.ast.Node
import asmble.util.Either
import asmble.util.add
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.invoke.MethodHandle

open class FuncBuilder {
    fun fromFunc(ctx: ClsContext, f: Node.Func, index: Int): Func {
        // TODO: validate local size?
        // TODO: initialize non-param locals?
        ctx.debug { "Building function ${ctx.funcName(index)}" }
        var func = Func(
            access = Opcodes.ACC_PRIVATE,
            name = ctx.funcName(index),
            params = f.type.params.map(Node.Type.Value::typeRef),
            ret = f.type.ret?.let(Node.Type.Value::typeRef) ?: Void::class.ref
        )
        // Rework the instructions
        val reworkedInsns = ctx.reworker.rework(ctx, f.instructions, f.type.ret)
        val funcCtx = FuncContext(
            cls = ctx,
            node = f,
            insns = reworkedInsns,
            memIsLocalVar =
                ctx.reworker.nonAdjacentMemAccesses(reworkedInsns) >= ctx.nonAdjacentMemAccessesRequiringLocalVar
        )

        // Add the mem as a local variable if necessary
        if (funcCtx.memIsLocalVar) func = func.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            VarInsnNode(Opcodes.ASTORE, funcCtx.actualLocalIndex(funcCtx.node.localsSize))
        )

        // Add all instructions
        ctx.debug { "Applying insns for function ${ctx.funcName(index)}" }
        // All functions have an implicit block
        func = funcCtx.insns.foldIndexed(func) { index, func, insn ->
            ctx.debug { "Applying insn $insn" }
            val ret = applyInsn(funcCtx, func, insn, index)
            ctx.trace { "Resulting stack: ${ret.stack}"}
            ret
        }

        // If the last instruction does not terminate, add the expected return
        if (func.insns.isEmpty() || !func.insns.last().isTerminating) {
            f.type.ret?.typeRef?.also { func = func.popExpecting(it) }
            func = func.addInsns(InsnNode(when (f.type.ret) {
                null -> Opcodes.RETURN
                Node.Type.Value.I32 -> Opcodes.IRETURN
                Node.Type.Value.I64 -> Opcodes.LRETURN
                Node.Type.Value.F32 -> Opcodes.FRETURN
                Node.Type.Value.F64 -> Opcodes.DRETURN
            }))
        }

        return func
    }

    fun applyInsn(ctx: FuncContext, fn: Func, i: Insn, index: Int) = when (i) {
        is Insn.Node ->
            applyNodeInsn(ctx, fn, i.insn, index)
        is Insn.ImportFuncRefNeededOnStack ->
            // Func refs are method handle fields
            fn.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName,
                    ctx.cls.funcName(i.index), MethodHandle::class.ref.asmDesc)
            ).push(MethodHandle::class.ref)
        is Insn.ImportGlobalSetRefNeededOnStack ->
            // Import setters are method handle fields
            fn.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName,
                    ctx.cls.importGlobalSetterFieldName(i.index), MethodHandle::class.ref.asmDesc)
            ).push(MethodHandle::class.ref)
        is Insn.ThisNeededOnStack ->
            fn.addInsns(VarInsnNode(Opcodes.ALOAD, 0)).push(ctx.cls.thisRef)
        is Insn.MemNeededOnStack ->
            putMemoryOnStackIfNecessary(ctx, fn)
    }

    fun applyNodeInsn(ctx: FuncContext, fn: Func, i: Node.Instr, index: Int) = when (i) {
        is Node.Instr.Unreachable ->
            fn.addInsns(UnsupportedOperationException::class.athrow("Unreachable"))
        is Node.Instr.Nop ->
            fn.addInsns(InsnNode(Opcodes.NOP))
        is Node.Instr.Block ->
            // TODO: check last item on stack?
            fn.pushBlock(i)
        is Node.Instr.Loop ->
            fn.pushBlock(i)
        is Node.Instr.If ->
            // The label is set in else or end
            fn.popExpecting(Int::class.ref).pushBlock(i).pushIf().addInsns(JumpInsnNode(Opcodes.IFEQ, null))
        is Node.Instr.Else ->
            applyElse(ctx, fn)
        is Node.Instr.End ->
            applyEnd(ctx, fn)
        is Node.Instr.Br ->
            applyBr(ctx, fn, i)
        is Node.Instr.BrIf ->
            applyBrIf(ctx, fn, i)
        is Node.Instr.BrTable ->
            applyBrTable(ctx, fn, i)
        is Node.Instr.Return ->
            applyReturnInsn(ctx, fn)
        is Node.Instr.Call ->
            applyCallInsn(ctx, fn, i.index)
        is Node.Instr.CallIndirect ->
            TODO("To be determined w/ invokedynamic")
        is Node.Instr.Drop ->
            fn.pop().let { (fn, popped) ->
                fn.addInsns(InsnNode(if (popped.stackSize == 2) Opcodes.POP2 else Opcodes.POP))
            }
        is Node.Instr.Select ->
            applySelectInsn(ctx, fn)
        is Node.Instr.GetLocal ->
            applyGetLocal(ctx, fn, i.index)
        is Node.Instr.SetLocal ->
            applySetLocal(ctx, fn, i.index)
        is Node.Instr.TeeLocal ->
            applyTeeLocal(ctx, fn, i.index)
        is Node.Instr.GetGlobal ->
            applyGetGlobal(ctx, fn, i.index)
        is Node.Instr.SetGlobal ->
            applySetGlobal(ctx, fn, i.index)
        is Node.Instr.I32Load, is Node.Instr.I64Load, is Node.Instr.F32Load, is Node.Instr.F64Load,
        is Node.Instr.I32Load8S, is Node.Instr.I32Load8U, is Node.Instr.I32Load16U, is Node.Instr.I32Load16S,
        is Node.Instr.I64Load8S, is Node.Instr.I64Load8U, is Node.Instr.I64Load16U, is Node.Instr.I64Load16S,
        is Node.Instr.I64Load32S, is Node.Instr.I64Load32U ->
            // TODO: why do I have to cast?
            applyLoadOp(ctx, fn, i as Node.Instr.Args.AlignOffset)
        is Node.Instr.I32Store, is Node.Instr.I64Store, is Node.Instr.F32Store, is Node.Instr.F64Store,
        is Node.Instr.I32Store8, is Node.Instr.I32Store16, is Node.Instr.I64Store8, is Node.Instr.I64Store16,
        is Node.Instr.I64Store32 ->
            applyStoreOp(ctx, fn, i as Node.Instr.Args.AlignOffset, index)
        is Node.Instr.CurrentMemory ->
            applyCurrentMemory(ctx, fn)
        is Node.Instr.GrowMemory ->
            applyGrowMemory(ctx, fn, index)
        is Node.Instr.I32Const ->
            fn.addInsns(i.value.const).push(Int::class.ref)
        is Node.Instr.I64Const ->
            fn.addInsns(i.value.const).push(Long::class.ref)
        is Node.Instr.F32Const ->
            fn.addInsns(i.value.const).push(Float::class.ref)
        is Node.Instr.F64Const ->
            fn.addInsns(i.value.const).push(Double::class.ref)
        is Node.Instr.I32Eqz ->
            applyI32UnaryCmp(ctx, fn, Opcodes.IFEQ)
        is Node.Instr.I32Eq ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPEQ)
        is Node.Instr.I32Ne ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPNE)
        is Node.Instr.I32LtS ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPLT)
        is Node.Instr.I32LtU ->
            applyI32CmpU(ctx, fn, Opcodes.IFLT)
        is Node.Instr.I32GtS ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPGT)
        is Node.Instr.I32GtU ->
            applyI32CmpU(ctx, fn, Opcodes.IFGT)
        is Node.Instr.I32LeS ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPLE)
        is Node.Instr.I32LeU ->
            applyI32CmpU(ctx, fn, Opcodes.IFLE)
        is Node.Instr.I32GeS ->
            applyI32CmpS(ctx, fn, Opcodes.IF_ICMPGE)
        is Node.Instr.I32GeU ->
            applyI32CmpU(ctx, fn, Opcodes.IFGE)
        is Node.Instr.I64Eqz ->
            fn.addInsns(0L.const).push(Long::class.ref).let { fn -> applyI64CmpS(ctx, fn, Opcodes.IFEQ) }
        is Node.Instr.I64Eq ->
            applyI64CmpS(ctx, fn, Opcodes.IFEQ)
        is Node.Instr.I64Ne ->
            applyI64CmpS(ctx, fn, Opcodes.IFNE)
        is Node.Instr.I64LtS ->
            applyI64CmpS(ctx, fn, Opcodes.IFLT)
        is Node.Instr.I64LtU ->
            applyI64CmpU(ctx, fn, Opcodes.IFLT)
        is Node.Instr.I64GtS ->
            applyI64CmpS(ctx, fn, Opcodes.IFGT)
        is Node.Instr.I64GtU ->
            applyI64CmpU(ctx, fn, Opcodes.IFGT)
        is Node.Instr.I64LeS ->
            applyI64CmpS(ctx, fn, Opcodes.IFLE)
        is Node.Instr.I64LeU ->
            applyI64CmpU(ctx, fn, Opcodes.IFLE)
        is Node.Instr.I64GeS ->
            applyI64CmpS(ctx, fn, Opcodes.IFGE)
        is Node.Instr.I64GeU ->
            applyI64CmpU(ctx, fn, Opcodes.IFGE)
        is Node.Instr.F32Eq ->
            applyF32Cmp(ctx, fn, Opcodes.IFEQ)
        is Node.Instr.F32Ne ->
            applyF32Cmp(ctx, fn, Opcodes.IFNE)
        is Node.Instr.F32Lt ->
            applyF32Cmp(ctx, fn, Opcodes.IFLT)
        is Node.Instr.F32Gt ->
            applyF32Cmp(ctx, fn, Opcodes.IFGT)
        is Node.Instr.F32Le ->
            applyF32Cmp(ctx, fn, Opcodes.IFLE)
        is Node.Instr.F32Ge ->
            applyF32Cmp(ctx, fn, Opcodes.IFGE)
        is Node.Instr.F64Eq ->
            applyF64Cmp(ctx, fn, Opcodes.IFEQ)
        is Node.Instr.F64Ne ->
            applyF64Cmp(ctx, fn, Opcodes.IFNE)
        is Node.Instr.F64Lt ->
            applyF64Cmp(ctx, fn, Opcodes.IFLT)
        is Node.Instr.F64Gt ->
            applyF64Cmp(ctx, fn, Opcodes.IFGT)
        is Node.Instr.F64Le ->
            applyF64Cmp(ctx, fn, Opcodes.IFLE)
        is Node.Instr.F64Ge ->
            applyF64Cmp(ctx, fn, Opcodes.IFGE)
        is Node.Instr.I32Clz ->
            // TODO Should make unsigned?
            applyI32Unary(ctx, fn, Integer::class.invokeStatic("numberOfLeadingZeros", Int::class, Int::class))
        is Node.Instr.I32Ctz ->
            applyI32Unary(ctx, fn, Integer::class.invokeStatic("numberOfTrailingZeros", Int::class, Int::class))
        is Node.Instr.I32Popcnt ->
            applyI32Unary(ctx, fn, Integer::class.invokeStatic("bitCount", Int::class, Int::class))
        is Node.Instr.I32Add ->
            applyI32Binary(ctx, fn, Opcodes.IADD)
        is Node.Instr.I32Sub ->
            applyI32Binary(ctx, fn, Opcodes.ISUB)
        is Node.Instr.I32Mul ->
            applyI32Binary(ctx, fn, Opcodes.IMUL)
        is Node.Instr.I32DivS ->
            applyI32Binary(ctx, fn, Opcodes.IDIV)
        is Node.Instr.I32DivU ->
            applyI32Binary(ctx, fn, Integer::class.invokeStatic("divideUnsigned", Int::class, Int::class, Int::class))
        is Node.Instr.I32RemS ->
            applyI32Binary(ctx, fn, Opcodes.IREM)
        is Node.Instr.I32RemU ->
            applyI32Binary(ctx, fn, Integer::class.invokeStatic("remainderUnsigned", Int::class, Int::class, Int::class))
        is Node.Instr.I32And ->
            applyI32Binary(ctx, fn, Opcodes.IAND)
        is Node.Instr.I32Or ->
            applyI32Binary(ctx, fn, Opcodes.IOR)
        is Node.Instr.I32Xor ->
            applyI32Binary(ctx, fn, Opcodes.IXOR)
        is Node.Instr.I32Shl ->
            applyI32Binary(ctx, fn, Opcodes.ISHL)
        is Node.Instr.I32ShrS ->
            applyI32Binary(ctx, fn, Opcodes.ISHR)
        is Node.Instr.I32ShrU ->
            applyI32Binary(ctx, fn, Opcodes.IUSHR)
        is Node.Instr.I32Rotl ->
            applyI32Binary(ctx, fn, Integer::class.invokeStatic("rotateLeft", Int::class, Int::class, Int::class))
        is Node.Instr.I32Rotr ->
            applyI32Binary(ctx, fn, Integer::class.invokeStatic("rotateRight", Int::class, Int::class, Int::class))
        is Node.Instr.I64Clz ->
            applyI64Unary(ctx, fn, java.lang.Long::class.invokeStatic("numberOfLeadingZeros", Int::class, Long::class))
        is Node.Instr.I64Ctz ->
            applyI64Unary(ctx, fn, java.lang.Long::class.invokeStatic("numberOfTrailingZeros", Int::class, Long::class))
        is Node.Instr.I64Popcnt ->
            applyI64Unary(ctx, fn, java.lang.Long::class.invokeStatic("bitCount", Int::class, Long::class))
        is Node.Instr.I64Add ->
            applyI64Binary(ctx, fn, Opcodes.LADD)
        is Node.Instr.I64Sub ->
            applyI64Binary(ctx, fn, Opcodes.LSUB)
        is Node.Instr.I64Mul ->
            applyI64Binary(ctx, fn, Opcodes.LMUL)
        is Node.Instr.I64DivS ->
            applyI64Binary(ctx, fn, Opcodes.LDIV)
        is Node.Instr.I64DivU ->
            applyI64Binary(ctx, fn, java.lang.Long::class.invokeStatic("divideUnsigned",
                Long::class, Long::class, Long::class))
        is Node.Instr.I64RemS ->
            applyI64Binary(ctx, fn, Opcodes.LREM)
        is Node.Instr.I64RemU ->
            applyI64Binary(ctx, fn, java.lang.Long::class.invokeStatic("remainderUnsigned",
                Long::class, Long::class, Long::class))
        is Node.Instr.I64And ->
            applyI64Binary(ctx, fn, Opcodes.LAND)
        is Node.Instr.I64Or ->
            applyI64Binary(ctx, fn, Opcodes.LOR)
        is Node.Instr.I64Xor ->
            applyI64Binary(ctx, fn, Opcodes.LXOR)
        is Node.Instr.I64Shl ->
            applyI64Binary(ctx, fn, Opcodes.LSHL)
        is Node.Instr.I64ShrS ->
            applyI64Binary(ctx, fn, Opcodes.LSHR)
        is Node.Instr.I64ShrU ->
            applyI64Binary(ctx, fn, Opcodes.LUSHR)
        is Node.Instr.I64Rotl ->
            applyI64Binary(ctx, fn, java.lang.Long::class.invokeStatic("rotateLeft",
                Long::class, Long::class, Int::class))
        is Node.Instr.I64Rotr ->
            applyI64Binary(ctx, fn, java.lang.Long::class.invokeStatic("rotateRight",
                Long::class, Long::class, Int::class))
        is Node.Instr.F32Abs ->
            applyF32Unary(ctx, fn, forceFnType<(Float) -> Float>(Math::abs).invokeStatic())
        is Node.Instr.F32Neg ->
            applyF32Unary(ctx, fn, InsnNode(Opcodes.FNEG))
        is Node.Instr.F32Ceil ->
            applyWithF32To64AndBack(ctx, fn) { fn -> applyF64Unary(ctx, fn, Math::ceil.invokeStatic()) }
        is Node.Instr.F32Floor ->
            applyWithF32To64AndBack(ctx, fn) { fn -> applyF64Unary(ctx, fn, Math::floor.invokeStatic()) }
        is Node.Instr.F32Trunc ->
            applyF32Trunc(ctx, fn)
        is Node.Instr.F32Nearest ->
            // TODO: this ain't right wrt infinity and other things
            applyF32Unary(ctx, fn, forceFnType<(Float) -> Int>(Math::round).invokeStatic()).
                addInsns(InsnNode(Opcodes.I2F))
        is Node.Instr.F32Sqrt ->
            applyWithF32To64AndBack(ctx, fn) { fn -> applyF64Unary(ctx, fn, Math::sqrt.invokeStatic()) }
        is Node.Instr.F32Add ->
            applyF32Binary(ctx, fn, Opcodes.FADD)
        is Node.Instr.F32Sub ->
            applyF32Binary(ctx, fn, Opcodes.FSUB)
        is Node.Instr.F32Mul ->
            applyF32Binary(ctx, fn, Opcodes.FMUL)
        is Node.Instr.F32Div ->
            applyF32Binary(ctx, fn, Opcodes.FDIV)
        is Node.Instr.F32Min ->
            applyF32Binary(ctx, fn, forceFnType<(Float, Float) -> Float>(Math::min).invokeStatic())
        is Node.Instr.F32Max ->
            applyF32Binary(ctx, fn, forceFnType<(Float, Float) -> Float>(Math::max).invokeStatic())
        is Node.Instr.F32CopySign ->
            applyF32Binary(ctx, fn, forceFnType<(Float, Float) -> Float>(Math::copySign).invokeStatic())
        is Node.Instr.F64Abs ->
            applyF64Unary(ctx, fn, forceFnType<(Double) -> Double>(Math::abs).invokeStatic())
        is Node.Instr.F64Neg ->
            applyF64Unary(ctx, fn, InsnNode(Opcodes.DNEG))
        is Node.Instr.F64Ceil ->
            applyF64Unary(ctx, fn, Math::ceil.invokeStatic())
        is Node.Instr.F64Floor ->
            applyF64Unary(ctx, fn, Math::floor.invokeStatic())
        is Node.Instr.F64Trunc ->
            applyF64Trunc(ctx, fn)
        is Node.Instr.F64Nearest ->
            // TODO: this ain't right wrt infinity and other things
            applyF64Unary(ctx, fn, forceFnType<(Double) -> Long>(Math::round).invokeStatic()).
                addInsns(InsnNode(Opcodes.L2D))
        is Node.Instr.F64Sqrt ->
            applyF64Unary(ctx, fn, Math::sqrt.invokeStatic())
        is Node.Instr.F64Add ->
            applyF64Binary(ctx, fn, Opcodes.DADD)
        is Node.Instr.F64Sub ->
            applyF64Binary(ctx, fn, Opcodes.DSUB)
        is Node.Instr.F64Mul ->
            applyF64Binary(ctx, fn, Opcodes.DMUL)
        is Node.Instr.F64Div ->
            applyF64Binary(ctx, fn, Opcodes.DDIV)
        is Node.Instr.F64Min ->
            applyF64Binary(ctx, fn, forceFnType<(Double, Double) -> Double>(Math::min).invokeStatic())
        is Node.Instr.F64Max ->
            applyF64Binary(ctx, fn, forceFnType<(Double, Double) -> Double>(Math::max).invokeStatic())
        is Node.Instr.F64CopySign ->
            applyF64Binary(ctx, fn, forceFnType<(Double, Double) -> Double>(Math::copySign).invokeStatic())
        is Node.Instr.I32WrapI64 ->
            applyConv(ctx, fn, Long::class.ref, Int::class.ref, Opcodes.L2I)
        is Node.Instr.I32TruncSF32 ->
            applyCheckedConv(ctx, fn, Float::class.ref, Int::class.ref, signed = true) ?:
                applyConv(ctx, fn, Float::class.ref, Int::class.ref, Opcodes.F2I)
        is Node.Instr.I32TruncUF32 ->
            // TODO: wat?
            applyConv(ctx, fn, Float::class.ref, Int::class.ref, Opcodes.F2I).
                addInsns(java.lang.Short::class.invokeStatic("toUnsignedInt", Int::class, Short::class))
        is Node.Instr.I32TruncSF64 ->
            applyConv(ctx, fn, Double::class.ref, Int::class.ref, Opcodes.D2I)
        is Node.Instr.I32TruncUF64 ->
            applyConv(ctx, fn, Double::class.ref, Int::class.ref, Opcodes.D2I).
                addInsns(java.lang.Short::class.invokeStatic("toUnsignedInt", Int::class, Short::class))
        is Node.Instr.I64ExtendSI32 ->
            applyConv(ctx, fn, Int::class.ref, Long::class.ref, Opcodes.I2L)
        is Node.Instr.I64ExtendUI32 ->
            applyConv(ctx, fn, Int::class.ref, Long::class.ref,
                Integer::class.invokeStatic("toUnsignedLong", Long::class, Int::class))
        is Node.Instr.I64TruncSF32 ->
            applyConv(ctx, fn, Float::class.ref, Long::class.ref, Opcodes.F2L)
        is Node.Instr.I64TruncUF32 ->
            applyConv(ctx, fn, Float::class.ref, Long::class.ref, Opcodes.F2L).
                addInsns(0xffL.const, InsnNode(Opcodes.LAND))
        is Node.Instr.I64TruncSF64 ->
            applyConv(ctx, fn, Double::class.ref, Long::class.ref, Opcodes.D2L)
        is Node.Instr.I64TruncUF64 ->
            // TODO: so wrong
            applyConv(ctx, fn, Double::class.ref, Long::class.ref, Opcodes.D2L).
                addInsns(0xffL.const, InsnNode(Opcodes.LAND))
        is Node.Instr.F32ConvertSI32 ->
            applyConv(ctx, fn, Int::class.ref, Float::class.ref, Opcodes.I2F)
        is Node.Instr.F32ConvertUI32 ->
            fn.addInsns(Integer::class.invokeStatic("toUnsignedLong", Long::class, Int::class)).
                let { applyConv(ctx, it, Int::class.ref, Float::class.ref, Opcodes.L2F) }
        is Node.Instr.F32ConvertSI64 ->
            applyConv(ctx, fn, Long::class.ref, Float::class.ref, Opcodes.L2F)
        is Node.Instr.F32ConvertUI64 ->
            fn.addInsns(0xffL.const, InsnNode(Opcodes.LAND)).
                let { applyConv(ctx, it, Long::class.ref, Float::class.ref, Opcodes.L2F) }
        is Node.Instr.F32DemoteF64 ->
            applyConv(ctx, fn, Double::class.ref, Float::class.ref, Opcodes.D2F)
        is Node.Instr.F64ConvertSI32 ->
            applyConv(ctx, fn, Int::class.ref, Double::class.ref, Opcodes.I2D)
        is Node.Instr.F64ConvertUI32 ->
            fn.addInsns(Integer::class.invokeStatic("toUnsignedLong", Long::class, Int::class)).
                let { applyConv(ctx, it, Int::class.ref, Double::class.ref, Opcodes.L2D) }
        is Node.Instr.F64ConvertSI64 ->
            applyConv(ctx, fn, Long::class.ref, Double::class.ref, Opcodes.L2D)
        is Node.Instr.F64ConvertUI64 ->
            fn.addInsns(0xffL.const, InsnNode(Opcodes.LAND)).
                let { applyConv(ctx, it, Long::class.ref, Double::class.ref, Opcodes.L2D) }
        is Node.Instr.F64PromoteF32 ->
            applyConv(ctx, fn, Float::class.ref, Double::class.ref, Opcodes.F2D)
        is Node.Instr.I32ReinterpretF32 ->
            applyConv(ctx, fn, Float::class.ref, Int::class.ref,
                java.lang.Float::class.invokeStatic("floatToRawIntBits", Int::class, Float::class))
        is Node.Instr.I64ReinterpretF64 ->
            applyConv(ctx, fn, Double::class.ref, Long::class.ref,
                java.lang.Double::class.invokeStatic("doubleToRawLongBits", Long::class, Double::class))
        is Node.Instr.F32ReinterpretI32 ->
            applyConv(ctx, fn, Int::class.ref, Float::class.ref,
                java.lang.Float::class.invokeStatic("intBitsToFloat", Float::class, Int::class))
        is Node.Instr.F64ReinterpretI64 ->
            applyConv(ctx, fn, Long::class.ref, Double::class.ref,
                java.lang.Double::class.invokeStatic("longBitsToDouble", Double::class, Long::class))
    }

    fun applyBr(ctx: FuncContext, fn: Func, i: Node.Instr.Br) =
        fn.blockAtDepth(i.relativeDepth).let { (fn, block) ->
            fn.addInsns(JumpInsnNode(Opcodes.GOTO, block.label)).let { fn ->
                // We only peek here, because we keep items on the stack for
                // dead code that uses it
                block.insnType?.typeRef?.let { typ ->
                    fn.peekExpecting(typ)
                    block.blockExitVals += i to typ
                }
                fn
            }
        }

    fun applyBrIf(ctx: FuncContext, fn: Func, i: Node.Instr.BrIf) =
        fn.blockAtDepth(i.relativeDepth).let { (fn, block) ->
            fn.popExpecting(Int::class.ref).let { fn ->
                // Must at least have the item on the stack that the block expects if it expects something
                val needsPopBeforeJump = needsToPopBeforeJumping(ctx, fn, block)
                val toLabel = if (needsPopBeforeJump) LabelNode() else block.label
                fn.addInsns(JumpInsnNode(Opcodes.IFNE, toLabel)).let { fn ->
                    block.insnType?.typeRef?.let {
                        fn.peekExpecting(it)
                        block.blockExitVals += i to it
                    }
                    if (needsPopBeforeJump) buildPopBeforeJump(ctx, fn, block, toLabel)
                    else fn
                }
            }
        }

    // Can compile quite cleanly as a table switch on the JVM
    fun applyBrTable(ctx: FuncContext, fn: Func, insn: Node.Instr.BrTable) =
        fn.blockAtDepth(insn.default).let { (fn, defaultBlock) ->
            defaultBlock.insnType?.typeRef?.let { defaultBlock.blockExitVals += insn to it }
            insn.targetTable.fold(fn to emptyList<LabelNode>()) { (fn, labels), targetDepth ->
                fn.blockAtDepth(targetDepth).let { (fn, targetBlock) ->
                    targetBlock.insnType?.typeRef?.let { targetBlock.blockExitVals += insn to it }
                    fn to (labels + targetBlock.label)
                }
            }.let { (fn, targetLabels) ->
                // In some cases, the target labels is empty. We need to make 0 goto
                // the default as well.
                val targetLabelsArr =
                    if (targetLabels.isNotEmpty()) targetLabels.toTypedArray()
                    else arrayOf(defaultBlock.label)
                fn.popExpecting(Int::class.ref).addInsns(TableSwitchInsnNode(0, targetLabelsArr.size - 1,
                    defaultBlock.label, *targetLabelsArr))
            }.let { fn ->
                // We only peek here, because we keep items on the stack for
                // dead code that uses it
                defaultBlock.insnType?.typeRef?.let { fn.peekExpecting(it) }
                fn
            }
        }

    fun needsToPopBeforeJumping(ctx: FuncContext, fn: Func, block: Func.Block.WithLabel): Boolean {
        val requiredStackCount = if (block.insnType == null) block.origStack.size else block.origStack.size + 1
        return fn.stack.size > requiredStackCount
    }

    fun buildPopBeforeJump(ctx: FuncContext, fn: Func, block: Func.Block.WithLabel, tempLabel: LabelNode): Func {
        // This is sad that we have to do this because we can't trust the wasm stack on nested breaks
        // Steps:
        // 1. Build a label, do a GOTO to it for the regular path
        // 2. Start the given temp label, do the pop, then goto the block label
        // 3. Resume code from the label at #1
        // NOTE: We have chosen to do it this way instead of negating the br_if conditional
        // or whatever because this should not happen in practice but in many tests there
        // are leftover stack items when doing nested breaks
        // TODO: make this better by moving this "pad" to the block end so we don't have
        // to make the running code path jump also. Also consider a better approach than
        // the constant swap-and-pop we do here.
        val requiredStackCount = if (block.insnType == null) block.origStack.size else block.origStack.size + 1
        ctx.debug {
            "Jumping to block requiring stack size $requiredStackCount but we " +
                "have ${fn.stack.size} so we are popping all unnecessary stack items before jumping"
        }
        // We actually have to pop the second to last, keeping the latest (unless it's empty)...and we do
        // this over and over, sadly, if there are more to discard
        val resumeLabel = LabelNode()
        return fn.addInsns(JumpInsnNode(Opcodes.GOTO, resumeLabel), tempLabel).withoutAffectingStack { fn ->
            (requiredStackCount until fn.stack.size).fold(fn) { fn, index ->
                if (fn.stack.size == 1) {
                    fn.addInsns(InsnNode(if (fn.stack.last().stackSize == 2) Opcodes.POP2 else Opcodes.POP)).pop(block).first
                } else fn.stackSwap(block).let { fn ->
                    fn.addInsns(InsnNode(if (fn.stack.last().stackSize == 2) Opcodes.POP2 else Opcodes.POP)).pop(block).first
                }
            }
        }.addInsns(
            JumpInsnNode(Opcodes.GOTO, block.label),
            resumeLabel
        )
    }

    fun applyElse(ctx: FuncContext, fn: Func) = fn.blockAtDepth(0).let { (fn, block) ->
        // Do a goto the end, and then add a fresh label to the initial "if" that jumps here
        // Also, put the stack back at what it was pre-if
        val label = LabelNode()
        fn.peekIf().label = label
        fn.addInsns(JumpInsnNode(Opcodes.GOTO, block.label), label).copy(stack = block.origStack)
    }

    fun applyEnd(ctx: FuncContext, fn: Func) = fn.popBlock().let { (fn, block) ->
        // Go over each exit and make sure it did the right thing
        block.blockExitVals.forEach {
            require(it.second == block.insnType?.typeRef) { "Block exit val was $it, expected ${block.insnType}" }
        }
        // We need to check the current stack
        when (block.insnType) {
            null -> {
                if (fn.stack != block.origStack) throw CompileErr.BlockEndMismatch(block.origStack, null, fn.stack)
                fn
            }
            else -> {
                val typ = block.insnType!!.typeRef
                val hasOrig = fn.stack == block.origStack
                val hasExpectedStart = !hasOrig && fn.stack.take(block.origStack.size + 1) == block.origStack + typ
                if (hasExpectedStart) {
                    // Extra stack items are always ok, because sometimes dead code
                    // works with them. Just pop off everything to get back down to
                    // size.
                    fn.stack.drop(block.origStack.size  + 1).fold(fn) { fn, _ -> fn.pop().first }
                } else if (hasOrig && block.blockExitVals.isNotEmpty()) {
                    // Exact orig stack is ok so long as there was at least one exit.
                    // This means that there was likely something internally that
                    // did an unconditional jump and some dead code after it consumed
                    // the stack.
                    fn.push(typ)
                } else throw CompileErr.BlockEndMismatch(block.origStack, typ, fn.stack)
            }
        }.let { fn ->
            when (block.insn) {
                is Node.Instr.Block ->
                    // Add label to end of block if it's there
                    block.label?.let { fn.addInsns(it) } ?: fn
                is Node.Instr.Loop ->
                    // Add label to beginning of loop if it's there
                    block.label?.let { fn.copy(insns = fn.insns.add(block.startIndex, it)) } ?: fn
                is Node.Instr.If -> fn.popIf().let { (fn, jumpNode) ->
                    when (block.label) {
                        // If there is no existing break label, add one to initial
                        // "if" only if it isn't there from an "else"
                        null -> if (jumpNode.label != null) fn else {
                            jumpNode.label = LabelNode()
                            fn.addInsns(jumpNode.label)
                        }
                        // If there is one, add it to the initial "if"
                        // if the "else" didn't set one on there...then push it
                        else -> {
                            if (jumpNode.label == null) jumpNode.label = block.label
                            fn.addInsns(block.label!!)
                        }
                    }
                }
                else -> error("Unrecognized end for ${block.insn}")
            }
        }
    }

    fun applyCheckedConv(ctx: FuncContext, fn: Func, from: TypeRef, to: TypeRef, signed: Boolean): Func? {
        if (!ctx.cls.checkConversionOverflow) return null
        // One approach would be to take the previous result and make sure it
        // was equal to the original. Another approach is to do the check inline.
        // But the best approach is a static synthetic function at the class level.
        if (from == Float::class.ref) {
            if (to == Int::class.ref) {
                if (signed) return fn.popExpecting(from).addInsns(ctx.cls.checkedF2SIConv).push(to)
            }
        }
        return null
    }

    fun applyConv(ctx: FuncContext, fn: Func, from: TypeRef, to: TypeRef, op: Int) =
        applyConv(ctx, fn, from, to, InsnNode(op))

    fun applyConv(ctx: FuncContext, fn: Func, from: TypeRef, to: TypeRef, insn: AbstractInsnNode) =
        fn.popExpecting(from).addInsns(insn).push(to)


    fun applyF64Trunc(ctx: FuncContext, fn: Func): Func {
        // The best way for now is a comparison and jump to ceil or floor sadly
        // So with it on the stack:
        // dup2
        // dconst 0
        // dcmpg
        // ifge label1
        // Math::ceil
        // goto label2
        // label1: Math::floor
        // label2
        val label1 = LabelNode()
        val label2 = LabelNode()
        return fn.popExpecting(Double::class.ref).addInsns(
            InsnNode(Opcodes.DUP2),
            0.0.const,
            InsnNode(Opcodes.DCMPG),
            JumpInsnNode(Opcodes.IFGE, label1),
            Math::ceil.invokeStatic(),
            JumpInsnNode(Opcodes.GOTO, label2),
            label1,
            Math::floor.invokeStatic(),
            label2
        ).push(Double::class.ref)
    }

    fun applyF32Trunc(ctx: FuncContext, fn: Func): Func {
        // Do the same as applyF64Trunc but convert where needed
        val label1 = LabelNode()
        val label2 = LabelNode()
        return fn.popExpecting(Float::class.ref).addInsns(
            InsnNode(Opcodes.DUP),
            0.0F.const,
            InsnNode(Opcodes.FCMPG),
            JumpInsnNode(Opcodes.IFGE, label1),
            InsnNode(Opcodes.F2D),
            Math::ceil.invokeStatic(),
            JumpInsnNode(Opcodes.GOTO, label2),
            label1,
            InsnNode(Opcodes.F2D),
            Math::floor.invokeStatic(),
            label2,
            InsnNode(Opcodes.D2F)
        ).push(Float::class.ref)
    }

    fun applyWithF32To64AndBack(ctx: FuncContext, fn: Func, f: (Func) -> Func) =
        fn.popExpecting(Float::class.ref).
            addInsns(InsnNode(Opcodes.F2D)).
            push(Double::class.ref).let(f).
            popExpecting(Double::class.ref).
            addInsns(InsnNode(Opcodes.D2F)).
            push(Float::class.ref)

    fun applyF64Binary(ctx: FuncContext, fn: Func, op: Int) =
        applyF64Binary(ctx, fn, InsnNode(op))

    fun applyF64Binary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyBinary(ctx, fn, Double::class.ref, insn)

    fun applyF32Binary(ctx: FuncContext, fn: Func, op: Int) =
        applyF32Binary(ctx, fn, InsnNode(op))

    fun applyF32Binary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyBinary(ctx, fn, Float::class.ref, insn)

    fun applyI64Binary(ctx: FuncContext, fn: Func, op: Int) =
        applyI64Binary(ctx, fn, InsnNode(op))

    fun applyI64Binary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyBinary(ctx, fn, Long::class.ref, insn)

    fun applyI32Binary(ctx: FuncContext, fn: Func, op: Int) =
        applyI32Binary(ctx, fn, InsnNode(op))

    fun applyI32Binary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyBinary(ctx, fn, Int::class.ref, insn)

    fun applyBinary(ctx: FuncContext, fn: Func, type: TypeRef, insn: AbstractInsnNode) =
        fn.popExpectingMulti(type, type).addInsns(insn).push(type)

    fun applyF64Unary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyUnary(ctx, fn, Double::class.ref, insn)

    fun applyF32Unary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyUnary(ctx, fn, Float::class.ref, insn)

    fun applyI64Unary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyUnary(ctx, fn, Long::class.ref, insn)

    fun applyI32Unary(ctx: FuncContext, fn: Func, insn: AbstractInsnNode) =
        applyUnary(ctx, fn, Int::class.ref, insn)

    fun applyUnary(ctx: FuncContext, fn: Func, type: TypeRef, insn: AbstractInsnNode) =
        fn.popExpecting(type).addInsns(insn).push(type)

    fun applyF32Cmp(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Float::class.ref).
            popExpecting(Float::class.ref).
            // TODO: test whether we need FCMPG instead
            addInsns(InsnNode(Opcodes.FCMPL)).
            push(Int::class.ref).
            let { fn -> applyI32UnaryCmp(ctx, fn, op) }

    fun applyF64Cmp(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Double::class.ref).
            popExpecting(Double::class.ref).
            // TODO: test whether we need DCMPG instead
            addInsns(InsnNode(Opcodes.DCMPL)).
            push(Int::class.ref).
            let { fn -> applyI32UnaryCmp(ctx, fn, op) }

    fun applyI64CmpU(ctx: FuncContext, fn: Func, op: Int) =
        applyCmpU(ctx, fn, op, Long::class.ref,
            java.lang.Long::class.invokeStatic("compareUnsigned", Int::class, Long::class, Long::class))

    fun applyI32CmpU(ctx: FuncContext, fn: Func, op: Int) =
        applyCmpU(ctx, fn, op, Int::class.ref,
            Integer::class.invokeStatic("compareUnsigned", Int::class, Int::class, Int::class))

    fun applyCmpU(ctx: FuncContext, fn: Func, op: Int, inTypes: TypeRef, meth: MethodInsnNode) =
        // Call the method, then compare with 0
        fn.popExpecting(inTypes).
            popExpecting(inTypes).
            addInsns(meth).
            push(Int::class.ref).
            let { fn -> applyI32UnaryCmp(ctx, fn, op) }

    fun applyI64CmpS(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Long::class.ref).
            popExpecting(Long::class.ref).
            addInsns(InsnNode(Opcodes.LCMP)).
            push(Int::class.ref).
            let { fn -> applyI32UnaryCmp(ctx, fn, op) }

    fun applyI32CmpS(ctx: FuncContext, fn: Func, op: Int) = applyCmpS(ctx, fn, op, Int::class.ref)

    fun applyCmpS(ctx: FuncContext, fn: Func, op: Int, inTypes: TypeRef): Func {
        val label1 = LabelNode()
        val label2 = LabelNode()
        return fn.popExpecting(inTypes).popExpecting(inTypes).addInsns(
            JumpInsnNode(op, label1),
            0.const,
            JumpInsnNode(Opcodes.GOTO, label2),
            label1,
            1.const,
            label2
        ).push(Int::class.ref)
    }

    fun applyI32UnaryCmp(ctx: FuncContext, fn: Func, op: Int): Func {
        // Ug: http://stackoverflow.com/questions/29131376/why-is-there-no-icmp-instruction
        // ifeq 0 label1
        // iconst_0
        // goto label2
        // label1: iconst_1
        // label2:
        val label1 = LabelNode()
        val label2 = LabelNode()
        return fn.popExpecting(Int::class.ref).addInsns(
            JumpInsnNode(op, label1),
            0.const,
            JumpInsnNode(Opcodes.GOTO, label2),
            label1,
            1.const,
            label2
        ).push(Int::class.ref)
    }

    fun applyGrowMemory(ctx: FuncContext, fn: Func, insnIndex: Int) =
        // Grow mem is a special case where the memory ref is already pre-injected on
        // the stack before this call. But it can have a memory leftover on the stack
        // so we pop it if we need to
        ctx.cls.mem.growMemory(ctx, fn).let { fn ->
            popMemoryIfNecessary(ctx, fn, ctx.insns.getOrNull(insnIndex + 1))
        }

    fun applyCurrentMemory(ctx: FuncContext, fn: Func) =
        // Curr mem is not specially injected, so we have to put the memory on the
        // stack since we need it
        putMemoryOnStackIfNecessary(ctx, fn).let { fn -> ctx.cls.mem.currentMemory(ctx, fn) }

    fun applyStoreOp(ctx: FuncContext, fn: Func, insn: Node.Instr.Args.AlignOffset, insnIndex: Int) =
        // Store is a special case where the memory ref is already pre-injected on
        // the stack before this call. But it can have a memory leftover on the stack
        // so we pop it if we need to
        ctx.cls.mem.storeOp(ctx, fn, insn).let { fn ->
            popMemoryIfNecessary(ctx, fn, ctx.insns.getOrNull(insnIndex + 1))
        }

    fun applyLoadOp(ctx: FuncContext, fn: Func, insn: Node.Instr.Args.AlignOffset) =
        // Load is a special case where the memory ref is already pre-injected on
        // the stack before this call
        ctx.cls.mem.loadOp(ctx, fn, insn)

    fun putMemoryOnStackIfNecessary(ctx: FuncContext, fn: Func) =
        if (fn.stack.lastOrNull() == ctx.cls.mem.memType) fn
        else if (ctx.memIsLocalVar)
        // Assume it's just past the locals
            fn.addInsns(VarInsnNode(Opcodes.ALOAD, ctx.actualLocalIndex(ctx.node.localsSize))).
                push(ctx.cls.mem.memType)
        else fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName, "memory", ctx.cls.mem.memType.asmDesc)
        ).push(ctx.cls.mem.memType)

    fun popMemoryIfNecessary(ctx: FuncContext, fn: Func, nextInsn: Insn?) =
        // We pop the mem if it's there and not a mem op next
        if (fn.stack.lastOrNull() != ctx.cls.mem.memType) fn else {
            val nextInstrRequiresMemOnStack = when (nextInsn) {
                is Insn.Node -> nextInsn.insn is Node.Instr.Args.AlignOffset ||
                    nextInsn.insn is Node.Instr.CurrentMemory || nextInsn.insn is Node.Instr.GrowMemory
                is Insn.MemNeededOnStack -> true
                else -> false
            }
            if (nextInstrRequiresMemOnStack) fn
            else fn.popExpecting(ctx.cls.mem.memType).addInsns(InsnNode(Opcodes.POP))
        }

    fun applySetGlobal(ctx: FuncContext, fn: Func, index: Int) = ctx.cls.globalAtIndex(index).let {
        when (it) {
            is Either.Left -> applyImportSetGlobal(ctx, fn, index, it.v.kind as Node.Import.Kind.Global)
            is Either.Right -> applySelfSetGlobal(ctx, fn, index, it.v)
        }
    }

    fun applySelfSetGlobal(ctx: FuncContext, fn: Func, index: Int, global: Node.Global) =
        // Just call putfield
        // Note, this is special and "this" has already been injected on the stack for us
        fn.popExpecting(global.type.contentType.typeRef).
            popExpecting(MethodHandle::class.ref).
            addInsns(
                FieldInsnNode(Opcodes.PUTFIELD, ctx.cls.thisRef.asmName, ctx.cls.globalName(index),
                    global.type.contentType.typeRef.asmDesc)
            )

    fun applyImportSetGlobal(ctx: FuncContext, fn: Func, index: Int, import: Node.Import.Kind.Global) =
        // Load the setter method handle field, then invoke it with stack val
        // Note, this is special and the method handle has already been injected on the stack for us
        fn.popExpecting(import.type.contentType.typeRef).
            popExpecting(MethodHandle::class.ref).
            addInsns(
                MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                    "(${import.type.contentType.typeRef.asmDesc})V", false)
            )

    fun applyGetGlobal(ctx: FuncContext, fn: Func, index: Int) = ctx.cls.globalAtIndex(index).let {
        when (it) {
            is Either.Left -> applyImportGetGlobal(ctx, fn, index, it.v.kind as Node.Import.Kind.Global)
            is Either.Right -> applySelfGetGlobal(ctx, fn, index, it.v)
        }
    }

    fun applySelfGetGlobal(ctx: FuncContext, fn: Func, index: Int, global: Node.Global) =
        fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName, ctx.cls.globalName(index),
                global.type.contentType.typeRef.asmDesc)
        ).push(global.type.contentType.typeRef)

    fun applyImportGetGlobal(ctx: FuncContext, fn: Func, index: Int, import: Node.Import.Kind.Global) =
        // Load the getter method handle field, then invoke it with nothing
        fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName,
                ctx.cls.importGlobalGetterFieldName(index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "()" + import.type.contentType.typeRef.asmDesc, false)
        ).push(import.type.contentType.typeRef)

    fun applyTeeLocal(ctx: FuncContext, fn: Func, index: Int) = ctx.node.localByIndex(index).typeRef.let { typeRef ->
        fn.addInsns(InsnNode(if (typeRef.stackSize == 2) Opcodes.DUP2 else Opcodes.DUP)).
            push(typeRef).let { applySetLocal(ctx, it, index) }
    }

    fun applySetLocal(ctx: FuncContext, fn: Func, index: Int) =
        fn.popExpecting(ctx.node.localByIndex(index).typeRef).let { fn ->
            when (ctx.node.localByIndex(index)) {
                Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ISTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LSTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FSTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DSTORE, ctx.actualLocalIndex(index)))
            }
        }

    fun applyGetLocal(ctx: FuncContext, fn: Func, index: Int) = when (ctx.node.localByIndex(index)) {
        Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ILOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LLOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FLOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DLOAD, ctx.actualLocalIndex(index)))
    }.push(ctx.node.localByIndex(index).typeRef)

    fun applySelectInsn(ctx: FuncContext, origFn: Func): Func {
        var fn = origFn
        // 3 things, first two must have same type, third is 0 check (0 means use second, otherwise use first)
        // What we'll do is:
        //   IFNE third L1 (which means if it's non-zero, goto L1)
        //   SWAP (or the double-style swap) (which means second is now first, and first is now second)
        //   L1:
        //   POP (or pop2, remove the last)

        // Check that we have an int for comparison, then the ref types
        val (ref1, ref2) = fn.popExpecting(Int::class.ref).pop().let { (newFn, ref1) ->
            newFn.pop().let { (newFn, ref2) -> fn = newFn; ref1 to ref2 }
        }
        require(ref1 == ref2) { "Select types do not match: $ref1 and $ref2" }

        val lbl = LabelNode()
        // Conditional jump
        return fn.addInsns(JumpInsnNode(Opcodes.IFNE, lbl),
            // Swap
            InsnNode(if (ref1.stackSize == 2) Opcodes.DUP2 else Opcodes.SWAP),
            // Label and pop
            lbl,
            InsnNode(if (ref1.stackSize == 2) Opcodes.POP2 else Opcodes.POP)
        )
    }

    fun applyCallInsn(ctx: FuncContext, fn: Func, index: Int) =
        // Imports use a MethodHandle field, others call directly
        ctx.cls.funcTypeAtIndex(index).let { funcType ->
            ctx.debug { "Applying call to ${ctx.cls.funcName(index)} of type $funcType with stack ${fn.stack}" }
            fn.popExpectingMulti(funcType.params.map(Node.Type.Value::typeRef)).let { fn ->
                when (ctx.cls.funcAtIndex(index)) {
                    is Either.Left -> fn.popExpecting(MethodHandle::class.ref).addInsns(
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName,
                            "invokeExact", funcType.asmDesc, false)
                    )
                    is Either.Right -> fn.popExpecting(ctx.cls.thisRef).addInsns(
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, ctx.cls.thisRef.asmName,
                            ctx.cls.funcName(index), funcType.asmDesc, false)
                    )
                }.let { fn -> funcType.ret?.let { fn.push(it.typeRef) } ?: fn }
            }
        }

    fun applyReturnInsn(ctx: FuncContext, fn: Func) = when (ctx.node.type.ret) {
        null ->
            fn.addInsns(InsnNode(Opcodes.RETURN))
        Node.Type.Value.I32 ->
            fn.popExpecting(Int::class.ref).addInsns(InsnNode(Opcodes.IRETURN))
        Node.Type.Value.I64 ->
            fn.popExpecting(Long::class.ref).addInsns(InsnNode(Opcodes.LRETURN))
        Node.Type.Value.F32 ->
            fn.popExpecting(Float::class.ref).addInsns(InsnNode(Opcodes.FRETURN))
        Node.Type.Value.F64 ->
            fn.popExpecting(Double::class.ref).addInsns(InsnNode(Opcodes.DRETURN))
    }.let {
        if (it.stack.isNotEmpty()) throw CompileErr.UnusedStackOnReturn(it.stack)
        it
    }

    fun buildF2SICheckedConv(ctx: ClsContext): MethodNode {
        /*
            public static int $$convF2SI(float f) {
                if (Float.isNaN(f)) throw new ArithmeticException("Invalid conversion to integer");
                if (f >= Integer.MAX_VALUE || f < Integer.MIN_VALUE)
                    throw new ArithmeticException("Integer overflow");
                return (int) f;
            }
        */
        val name = "\$\$convF2SI"
        val method = MethodNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_STATIC + Opcodes.ACC_SYNTHETIC,
            name, "(F)I", null, null)
        fun addInsns(vararg insn: AbstractInsnNode) = insn.forEach(method.instructions::add)
        fun throwArith(msg: String) = addInsns(
            TypeInsnNode(Opcodes.NEW, ArithmeticException::class.ref.asmName),
            InsnNode(Opcodes.DUP),
            msg.const,
            MethodInsnNode(Opcodes.INVOKESPECIAL, ArithmeticException::class.ref.asmName,
                "<init>", "(Ljava/lang/String;)V", false),
            InsnNode(Opcodes.ATHROW)
        )
        val firstIfSafe = LabelNode()
        val secondIfFail = LabelNode()
        val secondIfSafe = LabelNode()
        addInsns(
            // Check NaN and throw
            VarInsnNode(Opcodes.FLOAD, 0),
            MethodInsnNode(Opcodes.INVOKESTATIC, Float::class.javaObjectType.ref.asmName, "isNaN", "(F)Z", false),
            JumpInsnNode(Opcodes.IFEQ, firstIfSafe)
        )
        throwArith("Invalid conversion to integer")
        addInsns(
            firstIfSafe,
            // Check over or under int max/min
            VarInsnNode(Opcodes.FLOAD, 0),
            Integer.MAX_VALUE.toFloat().const,
            InsnNode(Opcodes.FCMPL),
            // TODO: So I can't store 2147483647.0f in an int? That seems wrong
            JumpInsnNode(Opcodes.IFGE, secondIfFail),
            VarInsnNode(Opcodes.FLOAD, 0),
            Integer.MIN_VALUE.toFloat().const,
            InsnNode(Opcodes.FCMPG),
            JumpInsnNode(Opcodes.IFGE, secondIfSafe),
            secondIfFail
        )
        throwArith("Integer overflow")
        addInsns(
            secondIfSafe,
            VarInsnNode(Opcodes.FLOAD, 0),
            InsnNode(Opcodes.F2I),
            InsnNode(Opcodes.IRETURN)
        )
        return method
    }

    companion object : FuncBuilder()
}