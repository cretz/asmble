package asmble.io

import asmble.ast.Node
import asmble.util.toRawIntBits
import asmble.util.toRawLongBits
import asmble.util.toUnsignedBigInt
import asmble.util.toUnsignedLong
import java.io.ByteArrayOutputStream

open class AstToBinary(val version: Long = 1L) {

    fun fromCustomSection(b: ByteWriter, n: Node.CustomSection) {
        b.writeVarUInt7(0)
        b.withVarUInt32PayloadSizePrepended { b ->
            b.writeString(n.name)
            b.writeBytes(n.payload)
        }
    }

    fun fromData(b: ByteWriter, n: Node.Data) {
        b.writeVarUInt32(n.index)
        fromInitExpr(b, n.offset)
        b.writeVarUInt32(n.data.size)
        b.writeBytes(n.data)
    }

    fun fromElem(b: ByteWriter, n: Node.Elem) {
        b.writeVarUInt32(n.index)
        fromInitExpr(b, n.offset)
        b.writeVarUInt32(n.funcIndices.size)
        n.funcIndices.forEach { b.writeVarUInt32(it) }
    }

    fun fromExport(b: ByteWriter, n: Node.Export) {
        b.writeString(n.field)
        b.writeByte(n.kind.externalKind)
        b.writeVarUInt32(n.index)
    }

    fun fromFuncBody(b: ByteWriter, n: Node.Func) {
        b.withVarUInt32PayloadSizePrepended { b ->
            val localsWithCounts = n.locals.fold(emptyList<Pair<Node.Type.Value, Int>>()) { localsWithCounts, local ->
                if (local != localsWithCounts.lastOrNull()?.first) localsWithCounts + (local to 1)
                else localsWithCounts.dropLast(1) + (local to localsWithCounts.last().second + 1)
            }
            b.writeVarUInt32(localsWithCounts.size)
            localsWithCounts.forEach { (localType, count) ->
                b.writeVarUInt32(count)
                b.writeVarInt7(localType.valueType)
            }
            n.instructions.forEach { fromInstr(b, it) }
            fromInstr(b, Node.Instr.End)
        }
    }

    fun fromFuncType(b: ByteWriter, n: Node.Type.Func) {
        b.writeVarInt7(-0x20)
        b.writeVarUInt32(n.params.size)
        n.params.forEach { b.writeVarInt7(it.valueType) }
        b.writeVarUInt1(n.ret != null)
        n.ret?.let { b.writeVarInt7(it.valueType) }
    }

    fun fromGlobal(b: ByteWriter, n: Node.Global) {
        fromGlobalType(b, n.type)
        fromInitExpr(b, n.init)
    }

    fun fromGlobalType(b: ByteWriter, n: Node.Type.Global) {
        b.writeVarInt7(n.contentType.valueType)
        b.writeVarUInt1(n.mutable)
    }

    fun fromImport(b: ByteWriter, import: Node.Import) {
        b.writeString(import.module)
        b.writeString(import.field)
        b.writeByte(import.kind.externalKind)
        when (import.kind) {
            is Node.Import.Kind.Func -> b.writeVarUInt32(import.kind.typeIndex)
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
        b.writeByte(op.opcode.toByte())
        fun <A : Node.Instr.Args> Node.InstrOp<A>.args() = this.argsOf(n)
        when (op) {
            is Node.InstrOp.ControlFlowOp.NoArg, is Node.InstrOp.ParamOp.NoArg,
            is Node.InstrOp.CompareOp.NoArg, is Node.InstrOp.NumOp.NoArg,
            is Node.InstrOp.ConvertOp.NoArg, is Node.InstrOp.ReinterpretOp.NoArg ->
                { }
            is Node.InstrOp.ControlFlowOp.TypeArg ->
                b.writeVarInt7(op.args().type.valueType)
            is Node.InstrOp.ControlFlowOp.DepthArg ->
                b.writeVarUInt32(op.args().relativeDepth)
            is Node.InstrOp.ControlFlowOp.TableArg -> op.args().let {
                b.writeVarUInt32(it.targetTable.size)
                it.targetTable.forEach { b.writeVarUInt32(it) }
                b.writeVarUInt32(it.default)
            }
            is Node.InstrOp.CallOp.IndexArg ->
                b.writeVarUInt32(op.args().index)
            is Node.InstrOp.CallOp.IndexReservedArg -> op.args().let {
                b.writeVarUInt32(it.index)
                b.writeVarUInt1(it.reserved)
            }
            is Node.InstrOp.VarOp.IndexArg ->
                b.writeVarUInt32(op.args().index)
            is Node.InstrOp.MemOp.AlignOffsetArg -> op.args().let {
                b.writeVarUInt32(it.align)
                b.writeVarUInt32(it.offset)
            }
            is Node.InstrOp.MemOp.ReservedArg ->
                b.writeVarUInt1(false)
            is Node.InstrOp.ConstOp.IntArg ->
                b.writeVarInt32(op.args().value)
            is Node.InstrOp.ConstOp.LongArg ->
                b.writeVarInt64(op.args().value)
            is Node.InstrOp.ConstOp.FloatArg ->
                b.writeUInt32(op.args().value.toRawIntBits().toUnsignedLong())
            is Node.InstrOp.ConstOp.DoubleArg ->
                b.writeUInt64(op.args().value.toRawLongBits().toUnsignedBigInt())
        }
    }

    fun <T> fromListSection(b: ByteWriter, n: List<T>, fn: (ByteWriter, T) -> Unit) {
        b.writeVarUInt32(n.size)
        n.forEach { fn(b, it) }
    }

    fun fromMemoryType(b: ByteWriter, n: Node.Type.Memory) {
        fromResizableLimits(b, n.limits)
    }

    fun fromModule(n: Node.Module) =
        ByteArrayOutputStream().also { fromModule(ByteWriter.OutputStream(it), n) }.toByteArray()

    fun fromModule(b: ByteWriter, n: Node.Module) {
        b.writeUInt32(0x6d736100)
        b.writeUInt32(version)
        // Sections
        // Add all custom sections after 0
        n.customSections.filter { it.afterSectionId == 0 }.forEach { fromCustomSection(b, it) }
        // We need to add all of the func decl types to the type list that are not already there
        val funcTypes = n.types + n.funcs.mapNotNull { if (n.types.contains(it.type)) null else it.type }
        wrapListSection(b, n, 1, funcTypes, this::fromFuncType)
        wrapListSection(b, n, 2, n.imports, this::fromImport)
        wrapListSection(b, n, 3, n.funcs) { b, f -> b.writeVarUInt32(funcTypes.indexOf(f.type)) }
        wrapListSection(b, n, 4, n.tables, this::fromTableType)
        wrapListSection(b, n, 5, n.memories, this::fromMemoryType)
        wrapListSection(b, n, 6, n.globals, this::fromGlobal)
        wrapListSection(b, n, 7, n.exports, this::fromExport)
        if (n.startFuncIndex != null)
            wrapSection(b, n, 8) { b -> b.writeVarUInt32(n.startFuncIndex) }
        wrapListSection(b, n, 9, n.elems, this::fromElem)
        wrapListSection(b, n, 10, n.funcs, this::fromFuncBody)
        wrapListSection(b, n, 11, n.data, this::fromData)
        n.names?.also { fromNames(b, it) }
        // All other custom sections after the previous
        n.customSections.filter { it.afterSectionId > 11 }.forEach { fromCustomSection(b, it) }
    }

    fun fromNames(b: ByteWriter, n: Node.NameSection) {
        fun <T> indexMap(b: ByteWriter, map: Map<Int, T>, fn: (T) -> Unit) {
            b.writeVarUInt32(map.size)
            map.forEach { index, v -> b.writeVarUInt32(index).also { fn(v) } }
        }
        fun nameMap(b: ByteWriter, map: Map<Int, String>) = indexMap(b, map) { b.writeString(it) }
        b.writeVarUInt7(0)
        b.withVarUInt32PayloadSizePrepended { b ->
            b.writeString("name")
            n.moduleName?.also { moduleName ->
                b.writeVarUInt7(0)
                b.withVarUInt32PayloadSizePrepended { b -> b.writeString(moduleName) }
            }
            if (n.funcNames.isNotEmpty()) b.writeVarUInt7(1).also {
                b.withVarUInt32PayloadSizePrepended { b -> nameMap(b, n.funcNames) }
            }
            if (n.localNames.isNotEmpty()) b.writeVarUInt7(2).also {
                b.withVarUInt32PayloadSizePrepended { b -> indexMap(b, n.localNames) { nameMap(b, it) } }
            }
        }
    }

    fun fromResizableLimits(b: ByteWriter, n: Node.ResizableLimits) {
        b.writeVarUInt1(n.maximum != null)
        b.writeVarUInt32(n.initial)
        n.maximum?.let { b.writeVarUInt32(it) }
    }

    fun fromTableType(b: ByteWriter, n: Node.Type.Table) {
        b.writeVarInt7(n.elemType.elemType)
        fromResizableLimits(b, n.limits)
    }

    fun <T> wrapListSection(
        b: ByteWriter,
        mod: Node.Module,
        sectionId: Short,
        n: List<T>,
        fn: (ByteWriter, T) -> Unit
    ) {
        // We wrap the section if it has items OR it has a custom section
        val hasCustomSection = mod.customSections.find { it.afterSectionId == sectionId.toInt() } != null
        if (n.isNotEmpty() || hasCustomSection) wrapSection(b, mod, sectionId) { b -> fromListSection(b, n, fn) }
    }

    fun wrapSection(
        b: ByteWriter,
        mod: Node.Module,
        sectionId: Short,
        handler: (ByteWriter) -> Unit
    ) {
        b.writeVarUInt7(sectionId)
        b.withVarUInt32PayloadSizePrepended(handler)
        // Add any custom sections after myself
        mod.customSections.filter { it.afterSectionId == sectionId.toInt() }.forEach { fromCustomSection(b, it) }
    }

    fun ByteWriter.writeVarUInt32(v: Int) {
        this.writeVarUInt32(v.toUnsignedLong())
    }

    fun ByteWriter.withVarUInt32PayloadSizePrepended(fn: (ByteWriter) -> Unit) {
        val temp = this.createTemp()
        fn(temp)
        this.writeVarUInt32(temp.written)
        this.write(temp)
    }

    fun ByteWriter.writeString(str: String) {
        val bytes = str.toByteArray()
        this.writeVarUInt32(bytes.size)
        this.writeBytes(bytes)
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