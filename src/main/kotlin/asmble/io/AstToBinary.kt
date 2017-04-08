package asmble.io

import asmble.ast.Node
import asmble.util.toRawIntBits
import asmble.util.toRawLongBits
import asmble.util.toUnsignedBigInt
import asmble.util.toUnsignedLong

open class AstToBinary(val version: Long = 0xd) {

    fun fromCustomSection(b: ByteWriter, n: Node.CustomSection) {
        b.writeVarUInt7("id", 0)
        val payloadLenIndex = b.index
        b.writeVarUInt32("payload_len", 0)
        val mark = b.index
        val nameBytes = n.name.toByteArray()
        b.writeVarUInt32("name_len", nameBytes.size)
        b.writeBytes("name", nameBytes)
        b.writeBytes("payload_data", n.payload)
        // Go back and write payload
        b.writeVarUInt32("payload_len", (b.index - mark), payloadLenIndex)
    }

    fun fromData(b: ByteWriter, n: Node.Data) {
        b.writeVarUInt32("index", n.index)
        fromInitExpr(b, n.offset)
        b.writeVarUInt32("size", n.data.size)
        b.writeBytes("data", n.data)
    }

    fun fromElem(b: ByteWriter, n: Node.Elem) {
        b.writeVarUInt32("index", n.index)
        fromInitExpr(b, n.offset)
        b.writeVarUInt32("num_elem", n.funcIndices.size)
        n.funcIndices.forEach { b.writeVarUInt32("elem", it) }
    }

    fun fromExport(b: ByteWriter, n: Node.Export) {
        val fieldBytes = n.field.toByteArray()
        b.writeVarUInt32("field_len", fieldBytes.size)
        b.writeBytes("field_str", fieldBytes)
        b.writeByte("kind", n.kind.externalKind)
        b.writeVarUInt32("index", n.index)
    }

    fun fromFuncBody(b: ByteWriter, n: Node.Func) {
        val bodySizeIndex = b.index
        b.writeVarUInt32("body_size", 0)
        val mark = b.index
        b.writeVarUInt32("local_count", n.locals.size)
        val localsWithCounts = n.locals.fold(emptyList<Pair<Node.Type.Value, Int>>()) { localsWithCounts, local ->
            if (local != localsWithCounts.lastOrNull()) localsWithCounts + (local to 1)
            else localsWithCounts.dropLast(1) + (local to localsWithCounts.last().second + 1)
        }
        require(localsWithCounts.distinctBy { it.first }.size != localsWithCounts.size) {
            "Not all types together for set of locals: ${n.locals}"
        }
        localsWithCounts.forEach { (localType, count) ->
            b.writeVarUInt32("count", count)
            b.writeVarInt7("type", localType.valueType)
        }
        n.instructions.forEach { fromInstr(b, it) }
        fromInstr(b, Node.Instr.End)
        b.writeVarUInt32("body_size", (b.index - mark), bodySizeIndex)
    }

    fun fromFuncType(b: ByteWriter, n: Node.Type.Func) {
        b.writeVarInt7("form", -0x20)
        b.writeVarUInt32("param_count", n.params.size)
        n.params.forEach { b.writeVarInt7("param_types", it.valueType) }
        b.writeVarUInt1("return_count", n.ret != null)
        n.ret?.let { b.writeVarInt7("return_type", it.valueType) }
    }

    fun fromGlobal(b: ByteWriter, n: Node.Global) {
        fromGlobalType(b, n.type)
        fromInitExpr(b, n.init)
    }

    fun fromGlobalType(b: ByteWriter, n: Node.Type.Global) {
        b.writeVarInt7("content_type", n.contentType.valueType)
        b.writeVarUInt1("mutability", n.mutable)
    }

    fun fromImport(b: ByteWriter, import: Node.Import) {
        val moduleBytes = import.module.toByteArray()
        b.writeVarUInt32("module_len", moduleBytes.size)
        b.writeBytes("module_str", moduleBytes)
        val fieldBytes = import.field.toByteArray()
        b.writeVarUInt32("field_len", fieldBytes.size)
        b.writeBytes("field_str", fieldBytes)
        b.writeByte("kind", import.kind.externalKind)
        when (import.kind) {
            is Node.Import.Kind.Func -> b.writeVarUInt32("type", import.kind.typeIndex)
            is Node.Import.Kind.Table -> fromTableType(b, import.kind.type)
            is Node.Import.Kind.Memory -> fromMemoryType(b, import.kind.type)
            is Node.Import.Kind.Global -> fromGlobalType(b, import.kind.type)
        }
    }

    fun fromInitExpr(b: ByteWriter, n: List<Node.Instr>) {
        require(n.size == 1) { "Init expression should have 1 insn, got ${n.size}" }
        fromInstr(b, n.single())
        fromInstr(b, Node.Instr.End)
    }

    fun fromInstr(b: ByteWriter, n: Node.Instr) {
        val op = n.op()
        b.writeVarUInt7("opcode", op.opcode)
        fun <A : Node.Instr.Args> Node.InstrOp<A>.args() = this.argsOf(n)
        when (op) {
            is Node.InstrOp.ControlFlowOp.NoArg, is Node.InstrOp.ParamOp.NoArg,
            is Node.InstrOp.CompareOp.NoArg, is Node.InstrOp.NumOp.NoArg,
            is Node.InstrOp.ConvertOp.NoArg, is Node.InstrOp.ReinterpretOp.NoArg ->
                { }
            is Node.InstrOp.ControlFlowOp.TypeArg ->
                b.writeVarInt7("block_type", op.args().type.valueType)
            is Node.InstrOp.ControlFlowOp.DepthArg ->
                b.writeVarUInt32("relative_depth", op.args().relativeDepth)
            is Node.InstrOp.ControlFlowOp.TableArg -> op.args().let {
                b.writeVarUInt32("target_count", it.targetTable.size)
                it.targetTable.forEach { b.writeVarUInt32("target_table", it) }
                b.writeVarUInt32("default_target", it.default)
            }
            is Node.InstrOp.CallOp.IndexArg ->
                b.writeVarUInt32("function_index", op.args().index)
            is Node.InstrOp.CallOp.IndexReservedArg -> op.args().let {
                b.writeVarUInt32("type_index", it.index)
                b.writeVarUInt1("reserved", it.reserved)
            }
            is Node.InstrOp.VarOp.IndexArg ->
                b.writeVarUInt32("index", op.args().index)
            is Node.InstrOp.MemOp.AlignOffsetArg -> op.args().let {
                b.writeVarUInt32("flags", it.align)
                b.writeVarUInt32("offset", it.offset)
            }
            is Node.InstrOp.MemOp.ReservedArg ->
                b.writeVarUInt1("reserved", false)
            is Node.InstrOp.ConstOp.IntArg ->
                b.writeVarInt32("value", op.args().value)
            is Node.InstrOp.ConstOp.LongArg ->
                b.writeVarInt64("value", op.args().value)
            is Node.InstrOp.ConstOp.FloatArg ->
                b.writeUInt32("value", op.args().value.toRawIntBits().toUnsignedLong())
            is Node.InstrOp.ConstOp.DoubleArg ->
                b.writeUInt64("value", op.args().value.toRawLongBits().toUnsignedBigInt())
        }
    }

    fun <T> fromListSection(b: ByteWriter, n: List<T>, fn: (ByteWriter, T) -> Unit) {
        b.writeVarUInt32("count", n.size)
        n.forEach { fn(b, it) }
    }

    fun fromMemoryType(b: ByteWriter, n: Node.Type.Memory) {
        fromResizableLimits(b, n.limits)
    }

    fun fromModule(b: ByteWriter, n: Node.Module) {
        b.writeUInt32("magic_number", 0x6d736100)
        b.writeUInt32("version", version)
        // Sections
        // Add all custom sections after 0
        n.customSections.filter { it.afterSectionId == 0 }.forEach { fromCustomSection(b, it) }
        // We need to add all of the func decl types to the type list that are not already there
        val funcTypes = n.types + n.funcs.mapNotNull { if (n.types.contains(it.type)) null else it.type }
        wrapListSection(b, n, 1, funcTypes, this::fromFuncType)
        wrapListSection(b, n, 2, n.imports, this::fromImport)
        wrapListSection(b, n, 3, n.funcs) { b, f -> b.writeVarUInt32("types", funcTypes.indexOf(f.type)) }
        wrapListSection(b, n, 4, n.tables, this::fromTableType)
        wrapListSection(b, n, 5, n.memories, this::fromMemoryType)
        wrapListSection(b, n, 6, n.globals, this::fromGlobal)
        wrapListSection(b, n, 7, n.exports, this::fromExport)
        if (n.startFuncIndex != null)
            wrapSection(b, n, 8) { b.writeVarUInt32("index", n.startFuncIndex) }
        wrapListSection(b, n, 9, n.elems, this::fromElem)
        wrapListSection(b, n, 10, n.funcs, this::fromFuncBody)
        wrapListSection(b, n, 11, n.data, this::fromData)
        // All other custom sections after the previous
        n.customSections.filter { it.afterSectionId > 11 }.forEach { fromCustomSection(b, it) }
    }

    fun fromResizableLimits(b: ByteWriter, n: Node.ResizableLimits) {
        b.writeVarUInt1("flags", n.maximum != null)
        b.writeVarUInt32("initial", n.initial)
        n.maximum?.let { b.writeVarUInt32("maximum", it) }
    }

    fun fromTableType(b: ByteWriter, n: Node.Type.Table) {
        b.writeVarInt7("element_type", n.elemType.elemType)
        fromResizableLimits(b, n.limits)
    }

    fun <T> wrapListSection(
        b: ByteWriter,
        mod: Node.Module,
        sectionId: Short,
        n: List<T>,
        fn: (ByteWriter, T) -> Unit
    ) {
        wrapSection(b, mod, sectionId) { fromListSection(b, n, fn) }
    }

    fun wrapSection(
        b: ByteWriter,
        mod: Node.Module,
        sectionId: Short,
        handler: () -> Unit
    ) {
        // Apply section
        b.writeVarUInt7("id", sectionId)
        val payloadLenIndex = b.index
        b.writeVarUInt32("payload_len", 0)
        val mark = b.index
        handler()
        // Go back and write payload
        b.writeVarUInt32("payload_len", (b.index - mark), payloadLenIndex)
        // Add any custom sections after myself
        mod.customSections.filter { it.afterSectionId == sectionId.toInt() }.forEach { fromCustomSection(b, it) }
    }

    fun ByteWriter.writeVarUInt32(field: String, v: Int, index: Int = this.index) {
        this.writeVarUInt32(field, v, index)
    }

    val Node.ExternalKind.externalKind: Byte get() = when(this) {
        Node.ExternalKind.FUNCTION -> 0
        Node.ExternalKind.TABLE -> 1
        Node.ExternalKind.MEMORY -> 2
        Node.ExternalKind.GLOBAL -> 3
    }

    val Node.Import.Kind.externalKind: Byte get() = when(this) {
        is Node.Import.Kind.Func -> 0
        is Node.Import.Kind.Table -> 1
        is Node.Import.Kind.Memory -> 2
        is Node.Import.Kind.Global -> 3
    }

    val Node.Type.Value?.valueType: Byte get() = when(this) {
        null -> -0x40
        Node.Type.Value.I32 -> -0x01
        Node.Type.Value.I64 -> -0x02
        Node.Type.Value.F32 -> -0x03
        Node.Type.Value.F64 -> -0x04
    }

    val Node.ElemType.elemType: Byte get() = when(this) {
        Node.ElemType.ANYFUNC -> -0x10
    }

    companion object : AstToBinary()
}