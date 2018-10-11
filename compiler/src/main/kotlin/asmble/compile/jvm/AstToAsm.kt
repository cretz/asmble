package asmble.compile.jvm

import asmble.annotation.WasmExport
import asmble.annotation.WasmExternalKind
import asmble.annotation.WasmImport
import asmble.annotation.WasmModule
import asmble.ast.Node
import asmble.io.AstToBinary
import asmble.io.ByteWriter
import asmble.util.Either
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.io.ByteArrayOutputStream
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

open class AstToAsm {
    // Note, the class does not have a name out of here (yet)
    fun fromModule(ctx: ClsContext) {
        // Invoke dynamic among other things
        ctx.cls.superName = Object::class.ref.asmName
        ctx.cls.version = Opcodes.V1_8
        ctx.cls.access += Opcodes.ACC_PUBLIC
        addFields(ctx)
        addConstructors(ctx)
        addFuncs(ctx)
        addExports(ctx)
        addAnnotations(ctx)
    }

    fun addFields(ctx: ClsContext) {
        // Mem field if present
        if (ctx.hasMemory)
            ctx.cls.fields.add(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, "memory",
                ctx.mem.memType.asmDesc, null, null))
        // Table field if present...
        // Private final for now, but likely won't be final in future versions supporting
        // mutable tables, may be not even a table but a list (and final)
        if (ctx.hasTable)
            ctx.cls.fields.add(FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, "table",
                Array<MethodHandle>::class.ref.asmDesc, null, null))
        // Now all method imports as method handles
        ctx.cls.fields.addAll(ctx.importFuncs.indices.map {
            FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, ctx.funcName(it),
                MethodHandle::class.ref.asmDesc, null, null)
        })
        // Now all import globals as getter (and maybe setter) method handles
        ctx.cls.fields.addAll(ctx.importGlobals.mapIndexed { index, import ->
            val getter = FieldNode(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, ctx.importGlobalGetterFieldName(index),
                MethodHandle::class.ref.asmDesc, null, null)
            if (!(import.kind as Node.Import.Kind.Global).type.mutable) listOf(getter)
            else listOf(getter, FieldNode(
                Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, ctx.importGlobalSetterFieldName(index),
                MethodHandle::class.ref.asmDesc, null, null))
        }.flatten())
        // Now all non-import globals
        ctx.cls.fields.addAll(ctx.mod.globals.mapIndexed { index, global ->
            val access = Opcodes.ACC_PRIVATE + if (!global.type.mutable) Opcodes.ACC_FINAL else 0
            FieldNode(access, ctx.globalName(ctx.importGlobals.size + index),
                global.type.contentType.typeRef.asmDesc, null, null)
        })
    }

    fun addConstructors(ctx: ClsContext) {
        // With no memory, we only have the constructor:
        //  <init>(imports...)
        // With memory we have at least two constructors:
        //  <init>(int maxMemory, imports...)
        //  <init>(MemClass mem, imports...)
        // If the max memory was supplied in the mem section, we also have
        //  <init>(imports...)

        if (ctx.mod.data.isNotEmpty()) ctx.assertHasMemory()
        if (!ctx.hasMemory) {
            addNoMemConstructor(ctx)
        } else {
            addMaxMemConstructor(ctx)
            addMemClassConstructor(ctx)
            // The default constructor is only allowed if the memory is not an import
            if (ctx.mod.memories.isNotEmpty()) addMemDefaultConstructor(ctx)
        }
    }

    fun addNoMemConstructor(ctx: ClsContext) {
        // <init>(imports...)
        var func = Func("<init>", constructorImportTypes(ctx)).addInsns(
            // Gotta call super()
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false)
        ).pushBlock(Node.Instr.Block(null), null, null)
        func = setConstructorGlobalImports(ctx, func, 0)
        func = setConstructorFunctionImports(ctx, func, 0)
        func = setConstructorTableImports(ctx, func, 0)
        func = initializeConstructorGlobals(ctx, func, 0)
        func = initializeConstructorTables(ctx, func, 0)
        func = executeConstructorStartFunction(ctx, func, 0)
        func = func.addInsns(InsnNode(Opcodes.RETURN))
        ctx.cls.methods.add(toConstructorNode(ctx, func))
    }

    fun addMaxMemConstructor(ctx: ClsContext) {
        // <init>(int maxMemory, imports...)
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
        ctx.cls.methods.add(toConstructorNode(ctx, func))
    }

    fun addMemClassConstructor(ctx: ClsContext) {
        // <init>(MemClass, imports...)
        var func = Func("<init>", listOf(ctx.mem.memType) + constructorImportTypes(ctx)).addInsns(
            // Gotta call super()
            VarInsnNode(Opcodes.ALOAD, 0),
            MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false)
        ).pushBlock(Node.Instr.Block(null), null, null)
        func = setConstructorGlobalImports(ctx, func, 1)
        func = setConstructorFunctionImports(ctx, func, 1)
        func = setConstructorTableImports(ctx, func, 1)
        func = initializeConstructorGlobals(ctx, func, 1)
        func = initializeConstructorTables(ctx, func, 1)

        // Set the mem field
        func = func.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            VarInsnNode(Opcodes.ALOAD, 1),
            FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            VarInsnNode(Opcodes.ALOAD, 1)
        ).push(ctx.mem.memType)

        // Do mem init only on non-import
        ctx.mod.memories.firstOrNull()?.let { func = ctx.mem.init(func, it.limits.initial) }

        // Add all data loads
        func = ctx.mod.data.fold(func) { origFunc, data ->
            // Add the mem on the stack if it's not already there
            val func =
                if (origFunc.stack.lastOrNull() == ctx.mem.memType) origFunc
                else origFunc.addInsns(VarInsnNode(Opcodes.ALOAD, 1)).push(ctx.mem.memType)
            // Ask mem to build the data, giving it a callback to put the offset on the stack
            ctx.mem.data(func, data.data) { func -> applyOffsetExpr(ctx, data.offset, func) }
        }
        // Take the mem off the stack if it's still left
        if (func.stack.lastOrNull() == ctx.mem.memType) func = func.popExpecting(ctx.mem.memType)

        func = executeConstructorStartFunction(ctx, func, 1)
        func = func.addInsns(InsnNode(Opcodes.RETURN))
        ctx.cls.methods.add(toConstructorNode(ctx, func))
    }

    fun addMemDefaultConstructor(ctx: ClsContext) {
        //<init>(imports...) only if there was a given max
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
        ctx.cls.methods.add(toConstructorNode(ctx, func))
    }

    fun constructorImportTypes(ctx: ClsContext) =
        ctx.importFuncs.map { MethodHandle::class.ref } +
        ctx.importGlobals.flatMap {
            // If it's mutable, it also comes with a setter
            if ((it.kind as? Node.Import.Kind.Global)?.type?.mutable == false) listOf(MethodHandle::class.ref)
            else listOf(MethodHandle::class.ref, MethodHandle::class.ref)
        } + ctx.mod.imports.filter { it.kind is Node.Import.Kind.Table }.map { Array<MethodHandle>::class.ref }

    fun toConstructorNode(ctx: ClsContext, func: Func) = mutableListOf<List<AnnotationNode>>().let { paramAnns ->
        // If the first param is a mem class and imported, add annotation
        // Otherwise if it is a mem class and not-imported or an int, no annotations
        // Otherwise do nothing because the rest of the params are imports
        func.params.firstOrNull()?.also { firstParam ->
            if (firstParam == Int::class.ref) {
                paramAnns.add(emptyList())
            } else if (firstParam == ctx.mem.memType) {
                val importMem = ctx.mod.imports.find { it.kind is Node.Import.Kind.Memory }
                if (importMem == null) paramAnns.add(emptyList())
                else paramAnns.add(listOf(importAnnotation(ctx, importMem)))
            }
        }
        // All non-mem imports one after another
        ctx.importFuncs.forEach { paramAnns.add(listOf(importAnnotation(ctx, it))) }
        ctx.importGlobals.forEach {
            paramAnns.add(listOf(importAnnotation(ctx, it)))
            // There are two annotations here if it's mutable
            if ((it.kind as? Node.Import.Kind.Global)?.type?.mutable == true)
                paramAnns.add(listOf(importAnnotation(ctx, it).also {
                    it.values.add("globalSetter")
                    it.values.add(true)
                }))
        }
        ctx.mod.imports.forEach {
            if (it.kind is Node.Import.Kind.Table) paramAnns.add(listOf(importAnnotation(ctx, it)))
        }
        func.toMethodNode().also { it.visibleParameterAnnotations = paramAnns.toTypedArray() }
    }

    fun importAnnotation(ctx: ClsContext, import: Node.Import) = AnnotationNode(WasmImport::class.ref.asmDesc).also {
        it.values = mutableListOf<Any>("module", import.module, "field", import.field)
        fun addValues(desc: String, limits: Node.ResizableLimits? = null) {
            it.values.add("desc")
            it.values.add(desc)
            if (limits != null) {
                it.values.add("resizableLimitInitial")
                it.values.add(limits.initial)
                if (limits.maximum != null) {
                    it.values.add("resizableLimitMaximum")
                    it.values.add(limits.maximum)
                }
            }
            it.values.add("kind")
            it.values.add(arrayOf(WasmExternalKind::class.ref.asmDesc, when (import.kind) {
                is Node.Import.Kind.Func -> WasmExternalKind.FUNCTION.name
                is Node.Import.Kind.Table -> WasmExternalKind.TABLE.name
                is Node.Import.Kind.Memory -> WasmExternalKind.MEMORY.name
                is Node.Import.Kind.Global -> WasmExternalKind.GLOBAL.name
            }))
        }
        when (import.kind) {
            is Node.Import.Kind.Func ->
                ctx.typeAtIndex(import.kind.typeIndex).let { addValues(it.asmDesc) }
            is Node.Import.Kind.Table ->
                addValues(Array<MethodHandle>::class.ref.asMethodRetDesc(), import.kind.type.limits)
            is Node.Import.Kind.Memory ->
                addValues(ctx.mem.memType.asMethodRetDesc(), import.kind.type.limits)
            is Node.Import.Kind.Global ->
                addValues(import.kind.type.contentType.typeRef.asMethodRetDesc())
        }
    }

    fun setConstructorGlobalImports(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.importGlobals.foldIndexed(func to ctx.importFuncs.size + paramsBeforeImports) {
            importIndex, (func, importParamOffset), import ->
            // Always a getter handle
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importParamOffset + 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.importGlobalGetterFieldName(importIndex), MethodHandle::class.ref.asmDesc)
            ).let { func ->
                // If it's mutable, it has a second setter handle
                if ((import.kind as? Node.Import.Kind.Global)?.type?.mutable == false) func to importParamOffset + 1
                else func.addInsns(
                    VarInsnNode(Opcodes.ALOAD, 0),
                    VarInsnNode(Opcodes.ALOAD, importParamOffset + 2),
                    FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                        ctx.importGlobalSetterFieldName(importIndex), MethodHandle::class.ref.asmDesc)
                ) to importParamOffset + 2
            }
        }.first

    fun setConstructorFunctionImports(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.importFuncs.indices.fold(func) { func, importIndex ->
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importIndex + paramsBeforeImports + 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    ctx.funcName(importIndex), MethodHandle::class.ref.asmDesc)
            )
        }

    fun setConstructorTableImports(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        if (ctx.mod.imports.none { it.kind is Node.Import.Kind.Table }) func else {
            val importIndex = ctx.importFuncs.size +
                // Mutable global imports have setters and take up two spots
                ctx.importGlobals.sumBy { if ((it.kind as? Node.Import.Kind.Global)?.type?.mutable == true) 2 else 1 } +
                paramsBeforeImports + 1
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                VarInsnNode(Opcodes.ALOAD, importIndex),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName,
                    "table", Array<MethodHandle>::class.ref.asmDesc)
            )
        }

    fun initializeConstructorGlobals(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        ctx.mod.globals.foldIndexed(func) { index, func, global ->
            // In the MVP, we can trust the init is constant stuff and a single instr
            if (global.init.size != 1) throw CompileErr.GlobalInitNotConstant(index)
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
                                        val paramOffset = ctx.importFuncs.size + paramsBeforeImports + 1 +
                                            ctx.importGlobals.take(it.index).sumBy {
                                                // Immutable jumps 1, mutable jumps 2
                                                if ((it.kind as? Node.Import.Kind.Global)?.type?.mutable == false) 1
                                                else 2
                                            }
                                        listOf(
                                            VarInsnNode(Opcodes.ALOAD, paramOffset),
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

    fun initializeConstructorTables(ctx: ClsContext, func: Func, paramsBeforeImports: Int): Func {
        val table = ctx.mod.tables.singleOrNull() ?:
            ctx.mod.imports.mapNotNull { it.kind as? Node.Import.Kind.Table }.firstOrNull()?.type
        if (table == null) {
            if (ctx.mod.elems.isNotEmpty()) throw CompileErr.UnknownTable(0)
            return func
        }

        // If it was in the module, we need to create the array and set it in the field
        if (ctx.mod.tables.isNotEmpty()) {
            // Create the array of the "initial" size and ignore max for now...
            return func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                table.limits.initial.const,
                TypeInsnNode(Opcodes.ANEWARRAY, MethodHandle::class.ref.asmName)
            ).let { func -> addElemsToTable(ctx, func, paramsBeforeImports) }.
                // Put it on the field (we know that we have this + arr on the stack)
                addInsns(
                    FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, "table",
                        Array<MethodHandle>::class.ref.asmDesc)
                )
        }
        // Otherwise, it was imported and we can set the elems on the imported one
        // from the parameter
        // TODO: I think this is a security concern and bad practice, may revisit (TODO: consider cloning the array)
        val importIndex = ctx.importFuncs.size + ctx.importGlobals.sumBy {
            // Immutable is 1, mutable is 2
            if ((it.kind as? Node.Import.Kind.Global)?.type?.mutable == false) 1 else 2
        } + paramsBeforeImports + 1
        return func.addInsns(VarInsnNode(Opcodes.ALOAD, importIndex)).
            let { func -> addElemsToTable(ctx, func, paramsBeforeImports) }.
            // Remove the array that's still there
            addInsns(InsnNode(Opcodes.POP))
    }

    // Can trust the array is on the stack and should leave it back on the stack
    fun addElemsToTable(ctx: ClsContext, func: Func, paramsBeforeImports: Int) =
        // Go over each elem and add all of the indices to the table
        ctx.mod.elems.fold(func) { func, elem ->
            require(elem.index == 0)
            // Due to requirements by the spec, if there are no function indices
            // we still have to ensure the offset is an int
            if (elem.funcIndices.isEmpty()) {
                // Just do the apply, but discard the result
                applyOffsetExpr(ctx, elem.offset, func).popExpecting(Int::class.ref)
            }
            // Lots of possible perf improvements including:
            // * Resolve the initial offset before running each func index
            // * Don't add to offset if it's just + 0
            // Could be a perf improvement here by resolving the offset before running each thing here
            elem.funcIndices.foldIndexed(func) { offsetIndex, func, funcIndex ->
                // Dup the array
                func.addInsns(InsnNode(Opcodes.DUP)).
                    // Load the initial offset
                    let { func -> applyOffsetExpr(ctx, elem.offset, func) }.
                    popExpecting(Int::class.ref).
                    // Add to the offset
                    addInsns(
                        offsetIndex.const,
                        InsnNode(Opcodes.IADD)
                    ).addInsns(
                    // Load the proper method handle based on whether it's an import or not
                    ctx.funcAtIndex(funcIndex).let { funcRef ->
                        // TODO: validate func type
                        val funcType = ctx.funcTypeAtIndex(funcIndex)
                        when (funcRef) {
                            is Either.Left ->
                                // Imports we can just get from the param
                                listOf(VarInsnNode(
                                    Opcodes.ALOAD,
                                    funcIndex + paramsBeforeImports + 1
                                ))
                            is Either.Right -> {
                                listOf(
                                    MethodHandles::lookup.invokeStatic(),
                                    VarInsnNode(Opcodes.ALOAD, 0),
                                    ctx.funcName(funcIndex).const,
                                    // Method type const, yay
                                    LdcInsnNode(Type.getMethodType(funcType.asmDesc)),
                                    MethodHandles.Lookup::bind.invokeVirtual()
                                )
                            }
                        }
                    }
                ).addInsns(
                    InsnNode(Opcodes.AASTORE)
                )
            }
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

    fun applyOffsetExpr(ctx: ClsContext, instrs: List<Node.Instr>, func: Func): Func {
        // Assert it's a valid offset
        if (instrs.size > 1) throw CompileErr.OffsetNotConstant()
        if (instrs.first() !is Node.Instr.Args.Const<*> && instrs.first() !is Node.Instr.GetGlobal)
            throw CompileErr.OffsetNotConstant()
        val tempFunc = Node.Func(
            type = Node.Type.Func(emptyList(), Node.Type.Value.I32),
            locals = emptyList(),
            instructions = instrs
        )
        val funcCtx = FuncContext(ctx, tempFunc, ctx.reworker.rework(ctx, tempFunc))
        return funcCtx.insns.foldIndexed(func) { index, func, insn ->
            ctx.funcBuilder.applyInsn(funcCtx, func, insn, index)
        }
    }

    fun addExports(ctx: ClsContext) {
        // Make sure there are no dupes
        ctx.mod.exports.fold(emptySet<String>()) { prev, exp ->
            if (prev.contains(exp.field)) throw CompileErr.DuplicateExport(exp.field)
            prev + exp.field
        }
        // Export all functions as named methods that delegate
        ctx.mod.exports.forEach {
            when (it.kind) {
                Node.ExternalKind.FUNCTION -> addExportFunc(ctx, it)
                Node.ExternalKind.GLOBAL -> addExportGlobal(ctx, it)
                Node.ExternalKind.MEMORY -> addExportMemory(ctx, it)
                Node.ExternalKind.TABLE -> addExportTable(ctx, it)
            }
        }
    }

    fun exportAnnotation(export: Node.Export) = AnnotationNode(WasmExport::class.ref.asmDesc).also {
        it.values = listOf(
            "value", export.field,
            "kind", arrayOf(WasmExternalKind::class.ref.asmDesc, export.kind.name)
        )
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
        method.visibleAnnotations = listOf(exportAnnotation(export))
        ctx.cls.methods.plusAssign(method)
    }

    fun addExportGlobal(ctx: ClsContext, export: Node.Export) {
        val global = ctx.globalAtIndex(export.index)
        val type = when (global) {
            is Either.Left -> (global.v.kind as Node.Import.Kind.Global).type
            is Either.Right -> global.v.type
        }
        // Create a simple getter
        val getter = MethodNode(Opcodes.ACC_PUBLIC, "get" + export.field.javaIdent.capitalize(),
            "()" + type.contentType.typeRef.asmDesc, null, null)
        getter.addInsns(VarInsnNode(Opcodes.ALOAD, 0))
        if (global is Either.Left) getter.addInsns(
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                ctx.importGlobalGetterFieldName(export.index), MethodHandle::class.ref.asmDesc),
            MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                "()" + type.contentType.typeRef.asmDesc, false)
        ) else getter.addInsns(
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, ctx.globalName(export.index),
                type.contentType.typeRef.asmDesc)
        )
        getter.addInsns(InsnNode(when (type.contentType) {
            Node.Type.Value.I32 -> Opcodes.IRETURN
            Node.Type.Value.I64 -> Opcodes.LRETURN
            Node.Type.Value.F32 -> Opcodes.FRETURN
            Node.Type.Value.F64 -> Opcodes.DRETURN
        }))
        getter.visibleAnnotations = listOf(exportAnnotation(export))
        ctx.cls.methods.plusAssign(getter)
        // If mutable, create simple setter
        if (type.mutable) {
            val setter = MethodNode(Opcodes.ACC_PUBLIC, "set" + export.field.javaIdent.capitalize(),
                "(${type.contentType.typeRef.asmDesc})V", null, null)
            setter.addInsns(VarInsnNode(Opcodes.ALOAD, 0))
            if (global is Either.Left) setter.addInsns(
                FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName,
                    ctx.importGlobalSetterFieldName(export.index), MethodHandle::class.ref.asmDesc),
                VarInsnNode(when (type.contentType) {
                    Node.Type.Value.I32 -> Opcodes.ILOAD
                    Node.Type.Value.I64 -> Opcodes.LLOAD
                    Node.Type.Value.F32 -> Opcodes.FLOAD
                    Node.Type.Value.F64 -> Opcodes.DLOAD
                }, 1),
                MethodInsnNode(Opcodes.INVOKEVIRTUAL, MethodHandle::class.ref.asmName, "invokeExact",
                    "(${type.contentType.typeRef.asmDesc})V", false),
                InsnNode(Opcodes.RETURN)
            ) else setter.addInsns(
                VarInsnNode(when (type.contentType) {
                    Node.Type.Value.I32 -> Opcodes.ILOAD
                    Node.Type.Value.I64 -> Opcodes.LLOAD
                    Node.Type.Value.F32 -> Opcodes.FLOAD
                    Node.Type.Value.F64 -> Opcodes.DLOAD
                }, 1),
                FieldInsnNode(Opcodes.PUTFIELD, ctx.thisRef.asmName, ctx.globalName(export.index),
                    type.contentType.typeRef.asmDesc),
                InsnNode(Opcodes.RETURN)
            )
            setter.visibleAnnotations = listOf(exportAnnotation(export))
            ctx.cls.methods.plusAssign(setter)
        }
    }

    fun addExportMemory(ctx: ClsContext, export: Node.Export) {
        // Create simple getter for the memory
        if (export.index != 0) throw CompileErr.UnknownMemory(export.index)
        val method = MethodNode(Opcodes.ACC_PUBLIC, "get" + export.field.javaIdent.capitalize(),
            "()" + ctx.mem.memType.asmDesc, null, null)
        method.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, "memory", ctx.mem.memType.asmDesc),
            InsnNode(Opcodes.ARETURN)
        )
        method.visibleAnnotations = listOf(exportAnnotation(export))
        ctx.cls.methods.plusAssign(method)
    }

    fun addExportTable(ctx: ClsContext, export: Node.Export) {
        // Create simple getter for the table
        if (export.index != 0) throw CompileErr.UnknownTable(export.index)
        val method = MethodNode(Opcodes.ACC_PUBLIC, "get" + export.field.javaIdent.capitalize(),
            "()" + Array<MethodHandle>::class.ref.asmDesc, null, null)
        method.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.thisRef.asmName, "table", Array<MethodHandle>::class.ref.asmDesc),
            InsnNode(Opcodes.ARETURN)
        )
        method.visibleAnnotations = listOf(exportAnnotation(export))
        ctx.cls.methods.plusAssign(method)
    }

    fun addFuncs(ctx: ClsContext) {
        ctx.cls.methods.addAll(ctx.mod.funcs.mapIndexed { index, func ->
            ctx.funcBuilder.fromFunc(ctx, func, ctx.importFuncs.size + index).toMethodNode()
        })
    }

    fun addAnnotations(ctx: ClsContext) {
        val annotationVals = mutableListOf<Any>()
        ctx.modName?.let { annotationVals.addAll(listOf("name", it)) }
        if (ctx.includeBinary) {
            // We are going to store this as a string of bytes in an annotation on the class. The linker
            // used to use this, but no longer does so it is opt-in for others to use. We choose to use an
            // annotation instead of an attribute for the same reasons Scala chose to make the switch in
            // 2.8+: Easier runtime reflection despite some size cost.
            annotationVals.addAll(listOf("binary", ByteArrayOutputStream().also {
                    ByteWriter.OutputStream(it).also { AstToBinary.fromModule(it, ctx.mod) }
            }.toByteArray().toString(Charsets.ISO_8859_1)))
        }
        ctx.cls.visibleAnnotations = listOf(
            AnnotationNode(WasmModule::class.ref.asmDesc).also { it.values = annotationVals }
        )
    }

    companion object : AstToAsm()
}