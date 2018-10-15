package asmble.run.jvm

import asmble.annotation.WasmExport
import asmble.annotation.WasmExternalKind
import asmble.ast.Node
import asmble.compile.jvm.Mem
import asmble.compile.jvm.javaIdent
import asmble.compile.jvm.ref
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

interface Module {
    val name: String?

    fun exportedFunc(field: String): MethodHandle?
    fun exportedGlobal(field: String): Pair<MethodHandle, MethodHandle?>?
    fun <T> exportedMemory(field: String, memClass: Class<T>): T?
    fun exportedTable(field: String): Array<MethodHandle?>?

    interface ImportResolver {
        fun resolveImportFunc(module: String, field: String, type: Node.Type.Func): MethodHandle
        fun resolveImportGlobal(
            module: String,
            field: String,
            type: Node.Type.Global
        ): Pair<MethodHandle, MethodHandle?>
        fun <T> resolveImportMemory(module: String, field: String, type: Node.Type.Memory, memClass: Class<T>): T
        fun resolveImportTable(module: String, field: String, type: Node.Type.Table): Array<MethodHandle?>
    }

    interface Instance : Module {
        val cls: Class<*>
        val inst: Any

        fun bindMethod(
            wasmName: String,
            wasmKind: WasmExternalKind,
            javaName: String = wasmName.javaIdent,
            paramCountRequired: Int? = null
        ) = cls.methods.filter {
            // @WasmExport match or just javaName match
            Modifier.isPublic(it.modifiers) &&
                !Modifier.isStatic(it.modifiers) &&
                (paramCountRequired == null || it.parameterCount == paramCountRequired) &&
                it.getDeclaredAnnotation(WasmExport::class.java).let { ann ->
                    if (ann == null) it.name == javaName else ann.value == wasmName && ann.kind == wasmKind
                }
        }.mapNotNull { MethodHandles.lookup().unreflect(it).bindTo(inst) }.singleOrNull()

        override fun exportedFunc(field: String) = bindMethod(field, WasmExternalKind.FUNCTION, field.javaIdent)
        override fun exportedGlobal(field: String) =
            bindMethod(field, WasmExternalKind.GLOBAL, "get" + field.javaIdent.capitalize(), 0)?.let {
                it to bindMethod(field, WasmExternalKind.GLOBAL, "set" + field.javaIdent.capitalize(), 1)
            }
        @SuppressWarnings("UNCHECKED_CAST")
        override fun <T> exportedMemory(field: String, memClass: Class<T>) =
            bindMethod(field, WasmExternalKind.MEMORY, "get" + field.javaIdent.capitalize(), 0)?.
                takeIf { it.type().returnType() == memClass }?.let { it.invokeWithArguments() as? T }
        @SuppressWarnings("UNCHECKED_CAST")
        override fun exportedTable(field: String) =
            bindMethod(field, WasmExternalKind.TABLE, "get" + field.javaIdent.capitalize(), 0)?.
                let { it.invokeWithArguments() as? Array<MethodHandle?> }
    }

    data class Native(override val cls: Class<*>, override val name: String?, override val inst: Any) : Instance {
        constructor(name: String?, inst: Any) : this(inst::class.java, name, inst)
    }

    class Compiled(
        val mod: Node.Module,
        override val cls: Class<*>,
        override val name: String?,
        val mem: Mem,
        imports: ImportResolver,
        val defaultMaxMemPages: Int = 1
    ) : Instance {
        override val inst = createInstance(imports)

        private fun createInstance(imports: ImportResolver): Any {
            // Find the constructor
            var constructorParams = emptyList<Any>()
            var constructor: Constructor<*>?

            // If there is a memory import, we have to get the one with the mem class as the first
            val memImport = mod.imports.find { it.kind is Node.Import.Kind.Memory }
            val memLimit = if (memImport != null) {
                constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull()?.ref == mem.memType }
                val memImportKind = memImport.kind as Node.Import.Kind.Memory
                val memInst = imports.resolveImportMemory(memImport.module, memImport.field,
                    memImportKind.type, Class.forName(mem.memType.asm.className))
                constructorParams += memInst
                val (memLimit, memCap) = mem.limitAndCapacity(memInst)
                if (memLimit < memImportKind.type.limits.initial * Mem.PAGE_SIZE)
                    throw RunErr.ImportMemoryLimitTooSmall(memImportKind.type.limits.initial * Mem.PAGE_SIZE, memLimit)
                memImportKind.type.limits.maximum?.let {
                    if (memCap > it * Mem.PAGE_SIZE)
                        throw RunErr.ImportMemoryCapacityTooLarge(it * Mem.PAGE_SIZE, memCap)
                }
                memLimit
            } else {
                // Find the constructor with no max mem amount (i.e. not int and not memory)
                constructor = cls.declaredConstructors.find {
                    val memClass = Class.forName(mem.memType.asm.className)
                    when (it.parameterTypes.firstOrNull()) {
                        Int::class.java, memClass -> false
                        else -> true
                    }
                }
                // If it is not there, find the one w/ the max mem amount
                val maybeMem = mod.memories.firstOrNull()
                if (constructor == null) {
                    val maxMem = Math.max(maybeMem?.limits?.initial ?: 0, defaultMaxMemPages)
                    constructor = cls.declaredConstructors.find { it.parameterTypes.firstOrNull() == Int::class.java }
                    constructorParams += maxMem * Mem.PAGE_SIZE
                }
                maybeMem?.limits?.initial?.let { it * Mem.PAGE_SIZE }
            }
            if (constructor == null) error("Unable to find suitable module constructor")

            // Function imports
            constructorParams += mod.imports.mapNotNull {
                if (it.kind is Node.Import.Kind.Func)
                    imports.resolveImportFunc(it.module, it.field, mod.types[it.kind.typeIndex])
                else null
            }

            // Global imports
            val globalImports = mod.imports.flatMap {
                if (it.kind is Node.Import.Kind.Global) {
                    imports.resolveImportGlobal(it.module, it.field, it.kind.type).toList().mapNotNull { it }
                } else emptyList()
            }
            constructorParams += globalImports

            // Table imports
            val tableImport = mod.imports.find { it.kind is Node.Import.Kind.Table }
            val tableSize = if (tableImport != null) {
                val tableImportKind = tableImport.kind as Node.Import.Kind.Table
                val table = imports.resolveImportTable(tableImport.module, tableImport.field, tableImportKind.type)
                if (table.size < tableImportKind.type.limits.initial)
                    throw RunErr.ImportTableTooSmall(tableImportKind.type.limits.initial, table.size)
                tableImportKind.type.limits.maximum?.let {
                    if (table.size > it) throw RunErr.ImportTableTooLarge(it, table.size)
                }
                constructorParams = constructorParams.plusElement(table)
                table.size
            } else mod.tables.firstOrNull()?.limits?.initial

            // We need to validate that elems can fit in table and data can fit in mem
            fun constIntExpr(insns: List<Node.Instr>): Int? = insns.singleOrNull()?.let {
                when (it) {
                    is Node.Instr.I32Const -> it.value
                    is Node.Instr.GetGlobal ->
                        if (it.index < globalImports.size) {
                            // Imports we already have
                            if (globalImports[it.index].type().returnType() == Int::class.java) {
                                globalImports[it.index].invokeWithArguments() as Int
                            } else null
                        } else constIntExpr(mod.globals[it.index - globalImports.size].init)
                    else -> null
                }
            }
            if (tableSize != null) mod.elems.forEach { elem ->
                constIntExpr(elem.offset)?.let { offset ->
                    if (offset + elem.funcIndices.size > tableSize)
                        throw RunErr.InvalidElemIndex(offset, elem.funcIndices.size, tableSize)
                }
            }
            if (memLimit != null) mod.data.forEach { data ->
                constIntExpr(data.offset)?.let { offset ->
                    if (offset < 0 || offset + data.data.size > memLimit)
                        throw RunErr.InvalidDataIndex(offset, data.data.size, memLimit)
                }
            }

            // Construct
            return constructor.newInstance(*constructorParams.toTypedArray())
        }
    }
}