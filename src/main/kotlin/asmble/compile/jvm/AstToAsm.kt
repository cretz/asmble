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
        // With no memory, we only have the constructor:
        //  <init>(funcImports..., memImports...)
        // With memory we have at least two constructors:
        //  <init>(int maxMemory, funcImports..., memImports...)
        //  <init>(MemClass mem, funcImports..., memImports...)
        // If the max memory was supplied in the mem section, we also have
        //  <init>(funcImports..., memImports...)

        if (ctx.mod.data.isNotEmpty()) ctx.assertHasMemory()
        if (!ctx.hasMemory) {
            addNoMemConstructor(ctx)
        } else {
            addMaxMemConstructor(ctx)
            addMemClassConstructor(ctx)
            addMemDefaultConstructor(ctx)
        }
    }

    fun addNoMemConstructor(ctx: ClsContext) {
        // <init>(funcImports..., memImports...)
        var func = Func("<init>", constructorImportTypes(ctx)).addInsns(
            // Gotta call super()
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false)
        ).pushBlock(Node.Instr.Block(null), null, null)
        func = setConstructorGlobalImports(ctx, func, 0)
        func = setConstructorFunctionImports(ctx, func, 0)
        func = initializeConstructorGlobals(ctx, func, 0)
        func = executeConstructorStartFunction(ctx, func, 0)
        func = func.addInsns(InsnNode(Opcodes.RETURN))
        ctx.cls.methods.add(func.toMethodNode())
    }

    fun addMaxMemConstructor(ctx: ClsContext) {
        // <init>(int maxMemory, funcImports..., memImports...)
        // Just call this(createMem(maxMemory), imports...)
        val importTypes = constructorImportTypes(ctx)
        var func = Func("<init>", listOf(Int::class.ref) + importTypes).addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ILOAD, 1)
        ).pushBlock(Node.Instr.Block(null), null, null).push(ctx.thisRef, Int::class.ref)
        func = ctx.mem.create(func).popExpectingMulti(ctx.thisRef, ctx.mem.memType)
        // In addition to this and mem on the stack, add all imports
        func = func.params.drop(1).indices.fold(func) { amountCon, index ->
            amountCon.addInsns(VarInsnNode(Opcodes.ALOAD, 2 + index))
        }
        // Make call
        val desc = "(${ctx.mem.memType.asmDesc}${importTypes.map { it.asmDesc }.joinToString("")})V"
        func = func.addInsns(
            MethodInsnNode(Opcodes.INVOKESPECIAL, ctx.thisRef.asmName, "<init>", desc, false),
            InsnNode(Opcodes.RETURN)
        )
        ctx.cls.methods.add(func.toMethodNode())
    }

    fun addMemClassConstructor(ctx: ClsContext) {
        // <init>(MemClass, funcImports..., memImports...)
        var func = Func("<init>", listOf(ctx.mem.memType) + constructorImportTypes(ctx)).addInsns(
            // Gotta call super()
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false)
        ).pushBlock(Node.Instr.Block(null), null, null)
        func = setConstructorGlobalImports(ctx, func, 1)
        func = setConstructorFunctionImports(ctx, func, 1)
        func = initializeConstructorGlobals(ctx, func, 1)

        // Set the mem field
        func = func.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ALOAD, 1),
            FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            VarInsnNode(Opcodes.ALOAD, 1)
        ).push(ctx.mem.memType)

        // Do mem init
        func = ctx.mem.init(func, ctx.mod.memories.firstOrNull()?.limits?.initial ?: 0)

        // Add all data loads
        func = ctx.mod.data.fold(func) { origFunc, data ->
            // Assert it's valid data
            if (data.offset.size > 1) throw CompileErr.DataInitNotConstant()
            if (data.offset.first() !is Node.Instr.Args.Const<*> && data.offset.first() !is Node.Instr.GetGlobal)
                throw CompileErr.DataInitNotConstant()
            // Add the mem on the stack if it's not already there
            val func =
                if (origFunc.stack.lastOrNull() == ctx.mem.memType) origFunc
                else origFunc.addInsns(VarInsnNode(Opcodes.ALOAD, 1)).push(ctx.mem.memType)
            // Ask mem to build the data, giving it a callback to put the offset on the stack
            ctx.mem.data(func, data.data) { func ->
                val tempFunc = Node.Func(
                    type = Node.Type.Func(emptyList(), Node.Type.Value.I32),
                    locals = emptyList(),
                    instructions = data.offset
                )
                val funcCtx = FuncContext(ctx, tempFunc, ctx.reworker.rework(ctx, tempFunc))
                funcCtx.insns.foldIndexed(func) { index, func, insn ->
                    ctx.funcBuilder.applyInsn(funcCtx, func, insn, index)
                }
            }
        }
        // Take the mem off the stack if it's still left
        if (func.stack.lastOrNull() == ctx.mem.memType) func = func.popExpecting(ctx.mem.memType)

        func = executeConstructorStartFunction(ctx, func, 1)
        func = func.addInsns(InsnNode(Opcodes.RETURN))
        ctx.cls.methods.add(func.toMethodNode())
    }

    fun addMemDefaultConstructor(ctx: ClsContext) {
        //<init>(funcImports..., memImports...) only if there was a given max
        // Just defer to the maxMem int one
        val memoryMax = ctx.mod.memories.first().limits.maximum ?: return
        val importTypes = constructorImportTypes(ctx)
        val desc = "(I${importTypes.map { it.asmDesc }.joinToString("")})V"
        val func = Func("<init>", importTypes).
            addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                (memoryMax * Mem.PAGE_SIZE).const
            ).
            addInsns(importTypes.indices.map { VarInsnNode(Opcodes.ALOAD, it + 1) }).
            addInsns(
                MethodInsnNode(Opcodes.INVOKESPECIAL, ctx.thisRef.asmName, "<init>", desc, false),
                InsnNode(Opcodes.RETURN)
            )
        ctx.cls.methods.add(func.toMethodNode())
    }

    fun constructorImportTypes(ctx: ClsContext) =
        ctx.importFuncs.map { MethodHandle::class.ref } +
        // We know it's only getters
        ctx.importGlobals.map { MethodHandle::class.ref }

    fun setConstructorGlobalImports(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.importGlobals.indices.fold(func) { func, importIndex ->
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, ctx.importFuncs.size + importIndex + paramsBeforeImports + 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.importGlobalGetterFieldName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }

    fun setConstructorFunctionImports(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.importFuncs.indices.fold(func) { func, importIndex ->
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importIndex + paramsBeforeImports + 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.funcName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }

    fun initializeConstructorGlobals(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.mod.globals.foldIndexed(func) { index, func, global ->
            // In the MVP, we can trust the init is constant stuff and a single instr
            if (global.init.size > 1) throw CompileErr.GlobalInitNotConstant(index)
            func.addInsns(VarInsnNode(Opcodes.ALOAD, 0)).
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
                                            VarInsnNode(
                                                Opcodes.ALOAD,
                                                ctx.importFuncs.size + it.index + paramsBeforeImports + 1
                                            ),
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
                            else -> throw CompileErr.GlobalInitNotConstant(index)
                        }
                    }
                ).addInsns(
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.globalName(ctx.importGlobals.size + index), global.type.contentType.typeRef.asmDesc)
            )
        }

    fun executeConstructorStartFunction(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        if (ctx.mod.startFuncIndex == null) func else {
            val funcType = ctx.funcTypeAtIndex(ctx.mod.startFuncIndex)
            if (funcType.params.isNotEmpty() || funcType.ret != null)
                throw CompileErr.InvalidStartFunctionType(ctx.mod.startFuncIndex)
            when (ctx.funcAtIndex(ctx.mod.startFuncIndex)) {
                is Either.Left ->
                    // This is an import, so we can just access it as a param
                    func.addInsns(
                        VarInsnNode(Opcodes.ALOAD, ctx.mod.startFuncIndex + paramsBeforeImports + 1),
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName,
                            "invokeExact", funcType.asmDesc, false)
                    )
                is Either.Right ->
                    // This is a local func, so invoke it virtual
                    func.addInsns(
                        VarInsnNode(Opcodes.ALOAD, 0),
                        MethodInsnNode(Opcodes.INVOKEVIRTUAL, ctx.thisRef.asmName,
                            ctx.funcName(ctx.mod.startFuncIndex), funcType.asmDesc, false)
                    )
            }
        }

    fun addExports(ctx: ClsContext) {
        // Export all functions as named methods that delegate
        ctx.mod.exports.forEach {
            when (it.kind) {
                Node.ExternalKind.FUNCTION -> addExportFunc(ctx, it)
                Node.ExternalKind.GLOBAL -> addExportGlobal(ctx, it)
                Node.ExternalKind.MEMORY -> addExportMemory(ctx, it)
                else -> TODO()
            }
        }
    }

    fun addExportFunc(ctx: ClsContext, export: Node.Export) {
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
        method.addInsns(InsnNode(when (funcType.ret) {
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
        val type = when (global) {
            is Either.Left -> (global.v.kind as Node.Import.Kind.Global).type
            is Either.Right -> global.v.type
        }
        if (type.mutable) throw CompileErr.MutableGlobalExport(export.index)
        // Create a simple getter
        val method = MethodNode(Opcodes.ACC_PUBLIC, "get" + export.field.javaIdent.capitalize(),
            "()" + type.contentType.typeRef.asmDesc, null, null)
        method.addInsns(VarInsnNode(Opcodes.ALOAD, 0))
        if (global is Either.Left) method.addInsns(
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                ctx.importGlobalGetterFieldName(export.index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "()" + type.contentType.typeRef.asmDesc, false)
        ) else method.addInsns(
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, ctx.globalName(export.index),
                type.contentType.typeRef.asmDesc)
        )
        method.addInsns(InsnNode(when (type.contentType) {
            Node.Type.Value.I32 -> Opcodes.IRETURN
            Node.Type.Value.I64 -> Opcodes.LRETURN
            Node.Type.Value.F32 -> Opcodes.FRETURN
            Node.Type.Value.F64 -> Opcodes.DRETURN
        }))
        ctx.cls.methods.plusAssign(method)
    }

    fun addExportMemory(ctx: ClsContext, export: Node.Export) {
        // Create simple getter for the memory
        require(export.index == 0) { "Only memory at index 0 supported" }
        val method = MethodNode(Opcodes.ACC_PUBLIC, "get" + export.field.javaIdent.capitalize(),
            "()" + ctx.mem.memType.asmDesc, null, null)
        method.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            InsnNode(Opcodes.ARETURN)
        )
        ctx.cls.methods.plusAssign(method)
    }

    fun addFuncs(ctx: ClsContext) {
        ctx.cls.methods.addAll(ctx.mod.funcs.mapIndexed { index, func ->
            ctx.funcBuilder.fromFunc(ctx, func, ctx.importFuncs.size + index).toMethodNode()
        })
    }

    companion object : AstToAsm()
}