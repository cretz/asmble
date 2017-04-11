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
        ctx.cls.superName = Object::class.ref.asmName
        ctx.cls.version = Opcodes.V1_7
        ctx.cls.access += Opcodes.ACC_PUBLIC
        addFields(ctx)
        addConstructors(ctx)
        addFuncs(ctx)
        addExports(ctx)
    }

    fun addFields(ctx: ClsContext) {
        // First field is always a private final memory field
        // Ug, ambiguity on List<?> +=
        ctx.cls.fields.add(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, "memory",
            ctx.mem.memType.asmDesc, null, null))
        // Now all method imports as method handles
        // TODO: why does this fail with asm-debug-all but not with just regular asm?
        ctx.cls.fields.addAll(ctx.importFuncs.indices.map {
            FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, ctx.funcName(it),
                MethodHandle::class.ref.asmDesc, null, null)
        })
        // Now all import globals as getter (and maybe setter) method handles
        ctx.cls.fields.addAll(ctx.importGlobals.mapIndexed { index, import ->
            if ((import.kind as Node.Import.Kind.Global).type.mutable) throw CompileErr.MutableGlobalImport(index)
            FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, ctx.importGlobalGetterFieldName(index),
                MethodHandle::class.ref.asmDesc, null, null)
        })
        // Now all non-import globals
        ctx.cls.fields.addAll(ctx.mod.globals.mapIndexed { index, global ->
            val access = Opcodes.ACC_PRIVATE + if (!global.type.mutable) Opcodes.ACC_FINAL else 0
            FieldNode(access, ctx.globalName(ctx.importGlobals.size + index),
                global.type.contentType.typeRef.asmDesc, null, null)
        })
    }

    fun addConstructors(ctx: ClsContext) {
        // We have at least two constructors:
        // <init>(int maxMemory, imports...)
        // <init>(MemClass mem, imports...)
        // If the max memory was supplied in the mem section, we also have
        // <init>(imports...)
        // TODO: what happens w/ more than 254 imports?

        val importTypes =
            ctx.importFuncs.map { MethodHandle::class.ref } +
            // We know it's only getters
            ctx.importGlobals.map { MethodHandle::class.ref }

        // <init>(MemClass maxMemory, imports...)
        // This is the one that actually does everything and is always deferred to
        // Set the mem field then call init then put it back on the stack
        var memCon = Func("<init>", listOf(ctx.mem.memType) + importTypes).addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ALOAD, 1),
            FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            VarInsnNode(Opcodes.ALOAD, 1)
        ).pushBlock(Node.Instr.Block(null), null, null).push(ctx.mem.memType)
        // Do mem init and remove it from the stack if it's still there afterwards
        memCon = ctx.mem.init(memCon, ctx.mod.memories.firstOrNull()?.limits?.initial ?: 0)
        // Add all data loads
        memCon = ctx.mod.data.fold(memCon) { origMemCon, data ->
            // Add the mem on the stack if it's not already there
            val memCon =
                if (origMemCon.stack.lastOrNull() == ctx.mem.memType) origMemCon
                else origMemCon.addInsns(VarInsnNode(Opcodes.ALOAD, 1)).push(ctx.mem.memType)
            // Ask mem to build the data, giving it a callback to put the offset on the stack
            ctx.mem.data(memCon, data.data) { memCon ->
                val func = Node.Func(
                    type = Node.Type.Func(emptyList(), Node.Type.Value.I32),
                    locals = emptyList(),
                    instructions = data.offset
                )
                val funcCtx = FuncContext(ctx, func, ctx.reworker.rework(ctx, func))
                funcCtx.insns.foldIndexed(memCon) { index, memCon, insn ->
                    ctx.funcBuilder.applyInsn(funcCtx, memCon, insn, index)
                }
            }
        }
        // Take the mem off the stack if it's still left
        if (memCon.stack.lastOrNull() == ctx.mem.memType) memCon = memCon.popExpecting(ctx.mem.memType)

        // Set all import functions
        memCon = ctx.importFuncs.indices.fold(memCon) { memCon, importIndex ->
            memCon.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importIndex + 2),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.funcName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }

        // Set all import globals
        memCon = ctx.importGlobals.indices.fold(memCon) { memCon, importIndex ->
            memCon.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, ctx.importFuncs.size + importIndex + 2),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.globalName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }

        // Initialize all globals
        memCon = ctx.mod.globals.foldIndexed(memCon) { index, memCon, global ->
            // In the MVP, we can trust the init is constant stuff and a single instr
            if (global.init.size > 1) throw CompileErr.GlobalInitNonConstant(index)
            memCon.addInsns(VarInsnNode(Opcodes.ALOAD, 0)).
                addInsns(
                    global.init.firstOrNull().let {
                        when (it) {
                            is Node.Instr.Args.Const<*> -> {
                                if (it.value::class.javaPrimitiveType?.ref != global.type.contentType.typeRef)
                                    throw CompileErr.GlobalConstantMismatch(
                                        index,
                                        global.type.contentType.typeRef,
                                        it.value::class.ref
                                    )
                                listOf(LdcInsnNode(it.value))
                            }
                            // Initialize from an import global means we'll just grab the MH as from the param and call
                            is Node.Instr.GetGlobal -> {
                                val refGlobal = ctx.globalAtIndex(it.index)
                                when (refGlobal) {
                                    is Either.Right -> throw CompileErr.UnknownGlobal(it.index)
                                    is Either.Left -> (refGlobal.v.kind as Node.Import.Kind.Global).let { refGlobalKind ->
                                        if (refGlobalKind.type.contentType != global.type.contentType)
                                            throw CompileErr.GlobalConstantMismatch(
                                                index,
                                                global.type.contentType.typeRef,
                                                refGlobalKind.type.contentType.typeRef
                                            )
                                        listOf(
                                            VarInsnNode(Opcodes.ALOAD, ctx.importFuncs.size + it.index + 2),
                                            MethodInsnNode(
                                                Opcodes.INVOKEVIRTUAL,
                                                MethodHandle::class.ref.asmName,
                                                "invokeExact",
                                                "()" + refGlobalKind.type.contentType.typeRef.asmDesc,
                                                false
                                            )
                                        )
                                    }
                                }
                            }
                            // Otherwise, global constant
                            null -> listOf(when (global.type.contentType) {
                                is Node.Type.Value.I32 -> 0.const
                                is Node.Type.Value.I64 -> 0L.const
                                is Node.Type.Value.F32 -> 0F.const
                                is Node.Type.Value.F64 -> 0.0.const
                            })
                            else -> throw CompileErr.GlobalInitNonConstant(index)
                        }
                    }
                ).addInsns(
                    FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                        ctx.globalName(ctx.importGlobals.size + index), global.type.contentType.typeRef.asmDesc)
                )
        }

        // Call object init
        memCon = memCon.addInsns(
            // Gotta call super()
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false),
            InsnNode(Opcodes.RETURN)
        )

        // <init>(int maxMemory, imports...)
        // Just call this(createMem(maxMemory), imports...)
        var amountCon = Func("<init>", listOf(Int::class.ref) + importTypes).addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ILOAD, 1)
        ).pushBlock(Node.Instr.Block(null), null, null).push(ctx.thisRef, Int::class.ref)
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
        ctx.mod.memories.firstOrNull()?.limits?.maximum?.also {
            val regCon = Func("<init>", importTypes).
                addInsns(
                    VarInsnNode(Opcodes.ALOAD, 0),
                    (it * Mem.PAGE_SIZE).const
                ).
                addInsns(importTypes.indices.map { VarInsnNode(Opcodes.ALOAD, it + 1) }).
                addInsns(
                    MethodInsnNode(Opcodes.INVOKESPECIAL, ctx.thisRef.asmName, amountCon.name, amountCon.desc, false),
                    InsnNode(Opcodes.RETURN)
                )
            constructors = listOf(regCon) + constructors
        }

        ctx.cls.methods.addAll(constructors.map(Func::toMethodNode))
    }

    fun addExports(ctx: ClsContext) {
        // Export all functions as named methods that delegate
        ctx.mod.exports.forEach {
            when (it.kind) {
                Node.ExternalKind.FUNCTION -> addExportFunc(ctx, it)
                Node.ExternalKind.GLOBAL -> addExportGlobal(ctx, it)
                else -> TODO()
            }
        }
    }

    fun addExportFunc(ctx: ClsContext, export: Node.Export) {
        // TODO: java safe name
        val funcType = ctx.funcTypeAtIndex(export.index)
        val method = MethodNode(Opcodes.ACC_PUBLIC, export.field.javaIdent, funcType.asmDesc, null, null)
        // Push all params
        funcType.params.fold(1) { stackIndex, param ->
            val op = when (param) {
                is Node.Type.Value.I32 -> Opcodes.ILOAD
                is Node.Type.Value.I64 -> Opcodes.LLOAD
                is Node.Type.Value.F32 -> Opcodes.FLOAD
                is Node.Type.Value.F64 -> Opcodes.DLOAD
            }
            method.instructions.add(VarInsnNode(op, stackIndex))
            stackIndex + param.typeRef.stackSize
        }
        // Imports use the field, otherwise it's an invoke on self
        ctx.funcAtIndex(export.index).let { func ->
            when (func) {
                is Either.Left -> {
                    // Prepend this.funcName field ref and append invokeExact
                    method.instructions.insert(FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                        ctx.funcName(export.index), MethodHandle::class.ref.asmDesc))
                    method.instructions.insert(VarInsnNode(Opcodes.ALOAD, 0))
                    method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName,
                        "invokeExact", funcType.asmDesc, false))
                }
                is Either.Right -> {
                    // Just prepend "this", and make call
                    method.instructions.insert(VarInsnNode(Opcodes.ALOAD, 0))
                    method.instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, ctx.thisRef.asmName,
                        ctx.funcName(export.index), funcType.asmDesc, false))
                }
            }
        }
        // Return as necessary
        method.instructions.add(InsnNode(when (funcType.ret) {
            null -> Opcodes.RETURN
            Node.Type.Value.I32 -> Opcodes.IRETURN
            Node.Type.Value.I64 -> Opcodes.LRETURN
            Node.Type.Value.F32 -> Opcodes.FRETURN
            Node.Type.Value.F64 -> Opcodes.DRETURN
        }))
        ctx.cls.methods.plusAssign(method)
    }

    fun addExportGlobal(ctx: ClsContext, export: Node.Export) {
        val global = ctx.globalAtIndex(export.index)
        when (global) {
            is Either.Left -> {
                if ((global.v.kind as Node.Import.Kind.Global).type.mutable)
                    throw CompileErr.MutableGlobalExport(export.index)
            }
            is Either.Right -> {
                if (global.v.type.mutable)
                    throw CompileErr.MutableGlobalExport(export.index)
            }
        }
        TODO()
    }

    fun addFuncs(ctx: ClsContext) {
        ctx.cls.methods.addAll(ctx.mod.funcs.mapIndexed { index, func ->
            ctx.funcBuilder.fromFunc(ctx, func, ctx.importFuncs.size + index).toMethodNode()
        })
    }

    companion object : AstToAsm()
}