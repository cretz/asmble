package asmble.compile.jvm

import asmble.ast.Node
import asmble.util.Either
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.lang.invoke.MethodHandle

open class AstToAsm {
    // Note, the class does not have a name out of here (yet)
    fun fromModule(ctx: ClsContext) {
        // Invoke dynamic among other things
        ctx.cls.version = Opcodes.V1_7
        ctx.cls.access += Opcodes.ACC_PUBLIC
        // TODO: make sure the imports are sorted as we expect
        addFields(ctx)
        addConstructors(ctx)
        addFuncs(ctx)
        // TODO: addImportForwarders
    }

    fun addFields(ctx: ClsContext) {
        // First field is always a private final memory field
        // Ug, ambiguity on List<?> +=
        ctx.cls.fields.plusAssign(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, "memory",
            ctx.mem.memType.asmDesc, null, null))
        // Now all method imports as method handles
        ctx.cls.fields += ctx.importFuncs.indices.map {
            FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, funcName(it),
                MethodHandle::class.ref.asmDesc, null, null)
        }
        // Now all import globals as getter (and maybe setter) method handles
        ctx.cls.fields += ctx.importGlobals.withIndex().flatMap { (index, import) ->
            val ret = listOf(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, importGlobalGetterFieldName(index),
                MethodHandle::class.ref.asmDesc, null, null))
            if (!(import.kind as Node.Import.Kind.Global).type.mutable) ret else {
                ret + FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, importGlobalSetterFieldName(index),
                    MethodHandle::class.ref.asmDesc, null, null)
            }
        }
        // Now all non-import globals
        ctx.cls.fields += ctx.mod.globals.withIndex().map { (index, global) ->
            // In the MVP, we can trust the init is constant stuff and a single instr
            require(global.init.size <= 1) { "Global init has more than 1 insn" }
            val init: Number = global.init.firstOrNull().let {
                when (it) {
                    is Node.Instr.Args.Const<*> -> it.value
                    null -> when (global.type.contentType) {
                        is Node.Type.Value.I32 -> 0
                        is Node.Type.Value.I64 -> 0L
                        is Node.Type.Value.F32 -> 0F
                        is Node.Type.Value.F64 -> 0.0
                    }
                    else -> throw RuntimeException("Unsupported init insn: $it")
                }
            }
            val access = Opcodes.ACC_PRIVATE + if (!global.type.mutable) Opcodes.ACC_FINAL else 0
            FieldNode(access, globalName(ctx.importGlobals.size + index),
                global.type.contentType.typeRef.asmDesc, null, init)
        }
    }

    fun addConstructors(ctx: ClsContext) {
        // We have at least two constructors:
        // <init>(int maxMemory, imports...)
        // <init>(MemClass maxMemory, imports...)
        // If the max memory was supplied in the mem section, we also have
        // <init>(imports...)
        // TODO: what happens w/ more than 254 imports?

        val importTypes = ctx.mod.imports.map {
            when (it.kind) {
                is Node.Import.Kind.Func -> MethodHandle::class.ref
                else -> TODO()
            }
        }

        // <init>(MemClass maxMemory, imports...)
        // Set the mem field then call init then put it back on the stack
        var memCon = Func("<init>", listOf(ctx.mem.memType) + importTypes).addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ALOAD, 1),
            FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            VarInsnNode(Opcodes.ALOAD, 1)
        ).push(ctx.mem.memType)
        // Do mem init and remove it from the stack if it's still there afterwards
        memCon = ctx.mem.init(memCon, ctx.mod.memories.first().limits.initial)
        if (memCon.stack.lastOrNull() == ctx.mem.memType) memCon = memCon.popExpecting(ctx.mem.memType)
        // Set all import functions
        memCon = ctx.importFuncs.indices.fold(memCon) { memCon, importIndex ->
            memCon.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importIndex + 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    funcName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }.addInsns(InsnNode(Opcodes.RETURN))

        // <init>(int maxMemory, imports...)
        // Just call this(createMem(maxMemory), imports...)
        var amountCon = Func("<init>", listOf(Int::class.ref) + importTypes).addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ILOAD, 1)
        ).push(ctx.thisRef, Int::class.ref)
        amountCon = ctx.mem.create(amountCon).popExpectingMulti(ctx.thisRef, ctx.mem.memType)
        // In addition to this and mem on the stack, add all imports
        amountCon = amountCon.params.drop(1).indices.fold(amountCon) { amountCon, index ->
            amountCon.addInsns(VarInsnNode(Opcodes.ALOAD, 2 + index))
        }
        // Make call
        amountCon = amountCon.addInsns(
            MethodInsnNode(Opcodes.INVOKESPECIAL, ctx.thisRef.asmName, memCon.name, memCon.desc, false),
            InsnNode(Opcodes.RETURN)
        )

        var constructors = listOf(amountCon, memCon)

        //<init>(imports...) only if there was a given max
        ctx.mod.memories.getOrNull(0)?.limits?.maximum?.also {
            val regCon = Func("<init>", importTypes).
                addInsns(VarInsnNode(Opcodes.ALOAD, 0)).
                addInsns(importTypes.indices.map { VarInsnNode(Opcodes.ALOAD, it + 1) }).
                addInsns(InsnNode(Opcodes.RETURN))
            constructors = listOf(regCon) + constructors
        }

        ctx.cls.methods += constructors.map(Func::toMethodNode)
    }

    fun addFuncs(ctx: ClsContext) {
        ctx.cls.methods += ctx.mod.funcs.mapIndexed { index, func ->
            fromFunc(ctx, func, ctx.importFuncs.size + index)
        }
    }

    fun importGlobalGetterFieldName(index: Int) = "import\$get" + globalName(index)
    fun importGlobalSetterFieldName(index: Int) = "import\$set" + globalName(index)
    fun globalName(index: Int) = "\$global$index"
    fun funcName(index: Int) = "\$func$index"

    fun fromFunc(ctx: ClsContext, f: Node.Func, index: Int): Func {
        // TODO: validate local size?
        // TODO: initialize non-param locals?
        var func = Func(
            access = Opcodes.ACC_PRIVATE,
            name = funcName(index),
            params = f.type.params.map(Node.Type.Value::typeRef),
            ret = f.type.ret?.let(Node.Type.Value::typeRef) ?: Void::class.ref
        )
        // Rework the instructions
        val funcCtx = FuncContext(ctx, f, ctx.reworker.rework(ctx, f.instructions))

        // Add all instructions
        func = funcCtx.insns.foldIndexed(func) { index, func, insn -> applyInsn(funcCtx, func, insn, index) }

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
                    funcName(i.index), MethodHandle::class.ref.asmDesc)
            ).push(MethodHandle::class.ref)
        is Insn.ImportGlobalSetRefNeededOnStack ->
            // Import setters are method handle fields
            fn.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName,
                    importGlobalSetterFieldName(i.index), MethodHandle::class.ref.asmDesc)
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
        // TODO: other control flow...
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
            fn.addInsns(0L.const).push(Long::class.ref).let { applyI64CmpS(ctx, fn, Opcodes.IFEQ) }
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
        else -> TODO()
    }

    fun applyF32Cmp(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Float::class.ref).
            popExpecting(Float::class.ref).
            // TODO: test whether we need FCMPG instead
            addInsns(InsnNode(Opcodes.FCMPL)).
            push(Int::class.ref).
            let { applyI32UnaryCmp(ctx, fn, op) }

    fun applyF64Cmp(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Double::class.ref).
            popExpecting(Double::class.ref).
            // TODO: test whether we need DCMPG instead
            addInsns(InsnNode(Opcodes.DCMPL)).
            push(Int::class.ref).
            let { applyI32UnaryCmp(ctx, fn, op) }
    
    fun applyI64CmpU(ctx: FuncContext, fn: Func, op: Int) =
        applyCmpU(ctx, fn, op, Long::class.ref, java.lang.Long::compareUnsigned.invokeStatic())

    fun applyI32CmpU(ctx: FuncContext, fn: Func, op: Int) =
        applyCmpU(ctx, fn, op, Int::class.ref, Integer::compareUnsigned.invokeStatic())

    fun applyCmpU(ctx: FuncContext, fn: Func, op: Int, inTypes: TypeRef, meth: MethodInsnNode) =
        // Call the method, then compare with 0
        fn.popExpecting(inTypes).
            popExpecting(inTypes).
            addInsns(meth).
            push(Int::class.ref).
            let { applyI32UnaryCmp(ctx, it, op) }

    fun applyI64CmpS(ctx: FuncContext, fn: Func, op: Int) =
        fn.popExpecting(Long::class.ref).
            popExpecting(Long::class.ref).
            addInsns(InsnNode(Opcodes.LCMP)).
            push(Int::class.ref).
            let { applyI32UnaryCmp(ctx, fn, op) }

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
        ctx.cls.mem.growMemory(fn).let { fn ->
            popMemoryIfNecessary(ctx, fn, ctx.insns.getOrNull(insnIndex + 1))
        }

    fun applyCurrentMemory(ctx: FuncContext, fn: Func) =
        // Curr mem is not specially injected, so we have to put the memory on the
        // stack since we need it
        putMemoryOnStackIfNecessary(ctx, fn).let { fn -> ctx.cls.mem.currentMemory(fn) }

    fun applyStoreOp(ctx: FuncContext, fn: Func, insn: Node.Instr.Args.AlignOffset, insnIndex: Int) =
        // Store is a special case where the memory ref is already pre-injected on
        // the stack before this call. But it can have a memory leftover on the stack
        // so we pop it if we need to
        ctx.cls.mem.storeOp(fn, insn).let { fn ->
            popMemoryIfNecessary(ctx, fn, ctx.insns.getOrNull(insnIndex + 1))
        }

    fun applyLoadOp(ctx: FuncContext, fn: Func, insn: Node.Instr.Args.AlignOffset) =
        // Loads are not specially injected, so we have to put the memory on the
        // stack since we need it
        putMemoryOnStackIfNecessary(ctx, fn).let { fn -> ctx.cls.mem.loadOp(fn, insn) }

    fun putMemoryOnStackIfNecessary(ctx: FuncContext, fn: Func) =
        if (fn.stack.lastOrNull() == ctx.cls.mem.memType) fn
        else if (fn.memIsLocalVar)
            // Assume it's just past the locals
            fn.addInsns(VarInsnNode(Opcodes.ALOAD, ctx.actualLocalIndex(ctx.node.locals.size))).
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
                FieldInsnNode(Opcodes.PUTFIELD, ctx.cls.thisRef.asmName, globalName(index),
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
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName, globalName(index),
                global.type.contentType.typeRef.asmDesc)
        ).push(global.type.contentType.typeRef)

    fun applyImportGetGlobal(ctx: FuncContext, fn: Func, index: Int, import: Node.Import.Kind.Global) =
        // Load the getter method handle field, then invoke it with nothing
        fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.thisRef.asmName,
                importGlobalGetterFieldName(index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "()" + import.type.contentType.typeRef.asmDesc, false)
        ).push(import.type.contentType.typeRef)

    fun applyTeeLocal(ctx: FuncContext, fn: Func, index: Int) = ctx.node.locals[index].typeRef.let { typeRef ->
        fn.addInsns(InsnNode(if (typeRef.stackSize == 2) Opcodes.DUP2 else Opcodes.DUP)).
            push(typeRef).let { applySetLocal(ctx, it, index) }
    }

    fun applySetLocal(ctx: FuncContext, fn: Func, index: Int) =
        fn.popExpecting(ctx.node.locals[index].typeRef).let { fn ->
            when (ctx.node.locals[index]) {
                Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ISTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LSTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FSTORE, ctx.actualLocalIndex(index)))
                Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DSTORE, ctx.actualLocalIndex(index)))
            }
        }

    fun applyGetLocal(ctx: FuncContext, fn: Func, index: Int) = when (ctx.node.locals[index]) {
        Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ILOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LLOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FLOAD, ctx.actualLocalIndex(index)))
        Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DLOAD, ctx.actualLocalIndex(index)))
    }.push(ctx.node.locals[index].typeRef)

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
            fn.popExpectingMulti(funcType.params.map(Node.Type.Value::typeRef)).let { fn ->
                when (ctx.cls.funcAtIndex(index)) {
                    is Either.Left -> fn.popExpecting(MethodHandle::class.ref).addInsns(
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName,
                            "invokeExact", funcType.asmDesc, false)
                    )
                    is Either.Right -> fn.popExpecting(ctx.cls.thisRef).addInsns(
                        MethodInsnNode(Opcodes.INVOKESTATIC, ctx.cls.thisRef.asmName,
                            funcName(index), funcType.asmDesc, false)
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
        require(it.stack.isEmpty()) { "Stack not empty on return" }
        it
    }

    companion object : AstToAsm()
}