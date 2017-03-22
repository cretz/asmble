package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.invoke.MethodHandle

open class AstToAsm {
    // Note, the class does not have a name out of here (yet)
    fun fromModule(ctx: Context) {
        // Invoke dynamic among other things
        ctx.cls.version = Opcodes.V1_7
        ctx.cls.access += Opcodes.ACC_PUBLIC
        // TODO: make sure the imports are sorted as we expect
        addFields(ctx)
        addConstructors(ctx)
        addFuncs(ctx)
        // TODO: addImportForwarders
    }

    fun addFields(ctx: Context) {
        // First field is always a private final memory field
        // Ug, ambiguity on List<?> +=
        ctx.cls.fields.plusAssign(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, "memory",
            ctx.mem.memType.asmDesc, null, null))
        // Now all method imports as method handles
        ctx.cls.fields += ctx.importFuncs.indices.map {
            FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, importFuncFieldName(it),
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

    fun addConstructors(ctx: Context) {
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
                    importFuncFieldName(importIndex), MethodHandle::class.ref.asmDesc)
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
        amountCon = amountCon.params.drop(1).foldIndexed(amountCon) { index, amountCon, param ->
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

    fun addFuncs(ctx: Context) {
        ctx.cls.methods += ctx.mod.funcs.mapIndexed { index, func ->
            fromFunc(ctx, func, ctx.importFuncs.size + index)
        }
    }

    fun importGlobalGetterFieldName(index: Int) = "import\$get" + globalName(index)
    fun importGlobalSetterFieldName(index: Int) = "import\$set" + globalName(index)
    fun globalName(index: Int) = "\$global$index"
    fun importFuncFieldName(index: Int) = "import" + funcName(index)
    fun funcName(index: Int) = "\$func$index"

    fun fromFunc(ctx: Context, f: Node.Func, index: Int): Func {
        // Technically all local funcs are static with "this" as the last param.
        // This is important because it allows us to call other functions without
        // reworking the stack. They are private, and if they are exported then
        // the parameters get turned around as expected.
        // TODO: validate local size?
        // TODO: initialize non-param locals?
        var func = Func(
            access = Opcodes.ACC_STATIC + Opcodes.ACC_PRIVATE,
            name = funcName(index),
            params = f.type.params.map(Node.Type.Value::typeRef) + ctx.thisRef,
            ret = f.type.ret?.let(Node.Type.Value::typeRef) ?: Void::class.ref
        )
        // Add all instructions
        func = f.instructions.fold(func) { func, insn -> applyInsn(ctx, f, func, insn) }
        return func
    }

    fun applyInsn(ctx: Context, f: Node.Func, fn: Func, i: Node.Instr) = when (i) {
        is Node.Instr.Unreachable ->
            fn.addInsns(UnsupportedOperationException::class.athrow("Unreachable"))
        is Node.Instr.Nop ->
            fn.addInsns(InsnNode(Opcodes.NOP))
        // TODO: other control flow...
        is Node.Instr.Return ->
            applyReturnInsn(ctx, f, fn)
        is Node.Instr.Call ->
            applyCallInsn(ctx, f, fn, i.index, false)
        is Node.Instr.CallIndirect ->
            applyCallInsn(ctx, f, fn, i.index, true)
        is Node.Instr.Drop ->
            fn.pop().let { (fn, popped) ->
                fn.addInsns(InsnNode(if (popped.stackSize == 2) Opcodes.POP2 else Opcodes.POP))
            }
        is Node.Instr.Select ->
            applySelectInsn(ctx, fn)
        is Node.Instr.GetLocal ->
            applyGetLocal(ctx, f, fn, i.index)
        is Node.Instr.SetLocal ->
            applySetLocal(ctx, f, fn, i.index)
        is Node.Instr.TeeLocal ->
            applyTeeLocal(ctx, f, fn, i.index)
        is Node.Instr.GetGlobal ->
            applyGetGlobal(ctx, fn, i.index)
        is Node.Instr.SetGlobal ->
            applySetGlobal(ctx, fn, i.index)
        else -> TODO()
    }

    fun applySetGlobal(ctx: Context, fn: Func, index: Int) =
        // Import is handled completeld differently than self
        // TODO: check mutability?
        if (index < ctx.importGlobals.size)
            applyImportSetGlobal(ctx, fn, index, ctx.importGlobals[index].kind as Node.Import.Kind.Global)
        else
            applySelfSetGlobal(ctx, fn, index, ctx.mod.globals[ctx.importGlobals.size - index])

    fun applySelfSetGlobal(ctx: Context, fn: Func, index: Int, global: Node.Global) =
        // We have to swap "this" with the value on the stack
        fn.addInsns(VarInsnNode(Opcodes.ALOAD, fn.lastParamLocalVarIndex)).
            push(ctx.thisRef).
            stackSwap().
            popExpecting(global.type.contentType.typeRef).
            addInsns(
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, globalName(index),
                    global.type.contentType.typeRef.asmDesc)
            )

    fun applyImportSetGlobal(ctx: Context, fn: Func, index: Int, import: Node.Import.Kind.Global) =
        // Load the setter method handle field, then invoke it with stack val
        fn.popExpecting(import.type.contentType.typeRef).addInsns(
            VarInsnNode(Opcodes.ALOAD, fn.lastParamLocalVarIndex),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                importGlobalSetterFieldName(index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "(${import.type.contentType.typeRef.asmDesc})V", false)
        )

    fun applyGetGlobal(ctx: Context, fn: Func, index: Int) =
        // Import is handled completely different than self
        if (index < ctx.importGlobals.size)
            applyImportGetGlobal(ctx, fn, index, ctx.importGlobals[index].kind as Node.Import.Kind.Global)
        else
            applySelfGetGlobal(ctx, fn, index, ctx.mod.globals[ctx.importGlobals.size - index])

    fun applySelfGetGlobal(ctx: Context, fn: Func, index: Int, global: Node.Global) =
        fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, fn.lastParamLocalVarIndex),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, globalName(index),
                global.type.contentType.typeRef.asmDesc)
        ).push(global.type.contentType.typeRef)

    fun applyImportGetGlobal(ctx: Context, fn: Func, index: Int, import: Node.Import.Kind.Global) =
        // Load the getter method handle field, then invoke it with nothing
        fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, fn.lastParamLocalVarIndex),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                importGlobalGetterFieldName(index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "()" + import.type.contentType.typeRef.asmDesc, false)
        ).push(import.type.contentType.typeRef)

    fun applyTeeLocal(ctx: Context, f: Node.Func, fn: Func, index: Int) = f.locals[index].typeRef.let { typeRef ->
        fn.addInsns(InsnNode(if (typeRef.stackSize == 2) Opcodes.DUP2 else Opcodes.DUP)).
            push(typeRef).let { applySetLocal(ctx, f, it, index) }
    }

    fun applySetLocal(ctx: Context, f: Node.Func, fn: Func, index: Int) =
        fn.popExpecting(f.locals[index].typeRef).let { fn ->
            when (f.locals[index]) {
                Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ISTORE, f.actualLocalIndex(index)))
                Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LSTORE, f.actualLocalIndex(index)))
                Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FSTORE, f.actualLocalIndex(index)))
                Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DSTORE, f.actualLocalIndex(index)))
            }
        }

    fun applyGetLocal(ctx: Context, f: Node.Func, fn: Func, index: Int) = when (f.locals[index]) {
        Node.Type.Value.I32 -> fn.addInsns(VarInsnNode(Opcodes.ILOAD, f.actualLocalIndex(index)))
        Node.Type.Value.I64 -> fn.addInsns(VarInsnNode(Opcodes.LLOAD, f.actualLocalIndex(index)))
        Node.Type.Value.F32 -> fn.addInsns(VarInsnNode(Opcodes.FLOAD, f.actualLocalIndex(index)))
        Node.Type.Value.F64 -> fn.addInsns(VarInsnNode(Opcodes.DLOAD, f.actualLocalIndex(index)))
    }.push(f.locals[index].typeRef)

    fun applySelectInsn(ctx: Context, origFn: Func): Func {
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
            LabelNode(lbl.label),
            InsnNode(if (ref1.stackSize == 2) Opcodes.POP2 else Opcodes.POP)
        )
    }

    fun applyCallInsn(ctx: Context, f: Node.Func, origFn: Func, index: Int, indirect: Boolean): Func {
        // Check whether it's an import or local to get type
        val funcType = ctx.importFuncs.getOrNull(index).let {
            when (it) {
                null -> ctx.mod.funcs.getOrNull(ctx.importFuncs.size - index)?.type
                else -> (it.kind as? Node.Import.Kind.Func)?.typeIndex?.let(ctx.mod.types::getOrNull)
            }
        } ?: throw RuntimeException("Cannot find func at index $index")
        // Check stack expectations
        var fn = origFn.popExpectingMulti(funcType.params.map(Node.Type.Value::typeRef))
        // Add "this" at the end and call statically
        fn = fn.addInsns(
            VarInsnNode(Opcodes.ALOAD, fn.lastParamLocalVarIndex),
            MethodInsnNode(Opcodes.INVOKESTATIC, ctx.thisRef.asmName,
                funcName(index), funcType.asmDesc, false)
        )
        // Return push on stack?
        funcType.ret?.also { fn = fn.push(it.typeRef) }
        return fn
    }

    fun applyReturnInsn(ctx: Context, f: Node.Func, fn: Func) = when (f.type.ret) {
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
        require(it.stack.isEmpty()) { "Stack not empty on void return" }
        it
    }

    data class Context(
        val packageName: String,
        val className: String,
        val mod: Node.Module,
        val cls: ClassNode,
        val mem: Mem = ByteBufferMem()
    ) {
        val importFuncs: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Func } }
        val importGlobals: List<Node.Import> by lazy { mod.imports.filter { it.kind is Node.Import.Kind.Global } }
        val thisRef = TypeRef(Type.getObjectType(packageName.replace('.', '/') + className))
    }

    companion object : AstToAsm()
}