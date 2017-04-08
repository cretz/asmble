package asmble.io

import asmble.ast.Node
import asmble.util.fromIntBits
import asmble.util.fromLongBits
import asmble.util.toIntExact
import asmble.util.toUnsignedShort

open class BinaryToAst(val version: Long = 0xd) {

    fun toBlockType(b: ByteReader) = b.readVarInt7("block_type").toInt().let {
        if (it == -0x40) null else toValueType(b, it)
    }

    fun toCustomSection(b: ByteReader, afterSectionId: Int) = Node.CustomSection(
        afterSectionId = afterSectionId,
        name = b.readString(),
        payload = b.readBytes("payload_data")
    )

    fun toData(b: ByteReader) = Node.Data(
        index = b.readVarUInt32AsInt("index"),
        offset = toInitExpr(b),
        data = b.readVarUInt32AsInt("size").let { b.readBytes("data", it) }
    )

    fun toElem(b: ByteReader) = Node.Elem(
        index = b.readVarUInt32AsInt("index"),
        offset = toInitExpr(b),
        funcIndices = b.readList { it.readVarUInt32AsInt("elems") }
    )

    fun toElemType(b: ByteReader) = b.readVarInt7("elem_type").toInt().let {
        when (it) {
            -0x10 -> Node.ElemType.ANYFUNC
            else -> error("Unrecognized elem type: $it")
        }
    }

    fun toExport(b: ByteReader) = Node.Export(
        field = b.readString(),
        kind = b.readByte("external_kind").toInt().let {
            when (it) {
                0 -> Node.ExternalKind.FUNCTION
                1 -> Node.ExternalKind.TABLE
                2 -> Node.ExternalKind.MEMORY
                3 -> Node.ExternalKind.GLOBAL
                else -> error("Unrecognized export kind: $it")
            }
        },
        index = b.readVarUInt32AsInt("index")
    )

    fun toFunc(b: ByteReader, type: Node.Type.Func) = Node.Func(
        type = type,
        locals = b.readList(this::toValueType),
        instructions = toInstrs(b).let { it.dropLast(1).also { require(it == listOf(Node.Instr.End)) } }
    )

    fun toFuncType(b: ByteReader): Node.Type.Func {
        require(b.readVarInt7("form").toInt() == -0x20)
        return Node.Type.Func(
            params = b.readList(this::toValueType),
            ret = if (b.readVarUInt1("return_count")) toValueType(b) else null
        )
    }

    fun toGlobal(b: ByteReader) = Node.Global(toGlobalType(b), toInitExpr(b))

    fun toGlobalType(b: ByteReader) = Node.Type.Global(
        contentType = toValueType(b),
        mutable = b.readVarUInt1("mutability")
    )

    fun toImport(b: ByteReader) = Node.Import(
        module = b.readString(),
        field = b.readString(),
        kind = b.readByte("external_kind").toInt().let {
            when (it) {
                0 -> Node.Import.Kind.Func(b.readVarUInt32AsInt("type"))
                1 -> Node.Import.Kind.Table(toTableType(b))
                2 -> Node.Import.Kind.Memory(toMemoryType(b))
                3 -> Node.Import.Kind.Global(toGlobalType(b))
                else -> error("Unrecognized import kind: $it")
            }
        }
    )

    fun toInitExpr(b: ByteReader) = listOf(toInstr(b)).also { require(toInstr(b) == Node.Instr.End) }

    fun toInstrs(b: ByteReader) = mutableListOf<Node.Instr>().also { while (!b.isEof) it += toInstr(b) }.toList()

    fun toInstr(b: ByteReader) = Node.InstrOp.op(b.readByte("opcode").toUnsignedShort()).let { op ->
        when (op) {
            is Node.InstrOp.ControlFlowOp.NoArg ->
                op.create
            is Node.InstrOp.ControlFlowOp.TypeArg ->
                op.create(toBlockType(b))
            is Node.InstrOp.ControlFlowOp.DepthArg ->
                op.create(b.readVarUInt32AsInt("relative_depth"))
            is Node.InstrOp.ControlFlowOp.TableArg -> op.create(
                b.readList { it.readVarUInt32AsInt("target_table") },
                b.readVarUInt32AsInt("default_target")
            )
            is Node.InstrOp.CallOp.IndexArg ->
                op.create(b.readVarUInt32AsInt("function_index"))
            is Node.InstrOp.CallOp.IndexReservedArg -> op.create(
                b.readVarUInt32AsInt("type_index"),
                b.readVarUInt1("reserved")
            )
            is Node.InstrOp.ParamOp.NoArg ->
                op.create
            is Node.InstrOp.VarOp.IndexArg ->
                op.create(b.readVarUInt32AsInt("index"))
            is Node.InstrOp.MemOp.AlignOffsetArg -> op.create(
                b.readVarUInt32AsInt("flags"),
                b.readVarUInt32("offset")
            )
            is Node.InstrOp.MemOp.ReservedArg ->
                op.create(b.readVarUInt1("reserved"))
            is Node.InstrOp.ConstOp.IntArg ->
                op.create(b.readVarInt32("value"))
            is Node.InstrOp.ConstOp.LongArg ->
                op.create(b.readVarInt64("value"))
            is Node.InstrOp.ConstOp.FloatArg ->
                op.create(Float.fromIntBits(b.readUInt32("value").toIntExact()))
            is Node.InstrOp.ConstOp.DoubleArg ->
                op.create(Double.fromLongBits(b.readUInt64("value").longValueExact()))
            is Node.InstrOp.CompareOp.NoArg ->
                op.create
            is Node.InstrOp.NumOp.NoArg ->
                op.create
            is Node.InstrOp.ConvertOp.NoArg ->
                op.create
            is Node.InstrOp.ReinterpretOp.NoArg ->
                op.create
        }
    }

    fun toMemoryType(b: ByteReader) = Node.Type.Memory(toResizableLimits(b))

    fun toModule(b: ByteReader): Node.Module {
        require(b.readUInt32("magic_number") == 0x6d736100L) { "Invalid magic number" }
        require(b.readUInt32("version") == version) { "Invalid version" }

        // Slice up all the sections
        var maxSectionId = 0
        var sections = emptyList<Pair<Int, ByteReader>>()
        while (!b.isEof) {
            val sectionId = b.readVarUInt7("id").toInt()
            if (sectionId != 0) require(sectionId > maxSectionId).also { maxSectionId = sectionId }
            sections += sectionId to b.slice("payload_data", b.readVarUInt32AsInt("payload_len"))
        }

        // Now build the module
        fun <T> readSectionList(sectionId: Int, fn: (ByteReader) -> T) =
            sections.find { it.first == sectionId }?.second?.readList(fn) ?: emptyList()
        val types = readSectionList(1, this::toFuncType)
        val funcIndices = readSectionList(3) { it.readVarUInt32AsInt("types") }
        return Node.Module(
            types = types,
            imports = readSectionList(2, this::toImport),
            tables = readSectionList(4, this::toTableType),
            memories = readSectionList(5, this::toMemoryType),
            globals = readSectionList(6, this::toGlobal),
            exports = readSectionList(7, this::toExport),
            startFuncIndex = sections.find { it.first == 8 }?.second?.readVarUInt32AsInt("index"),
            elems = readSectionList(9, this::toElem),
            funcs = readSectionList(10) { it }.zip(funcIndices.map { types[it] }, this::toFunc),
            data = readSectionList(11, this::toData),
            customSections = sections.foldIndexed(emptyList()) { index, customSections, (sectionId, b) ->
                if (sectionId != 0) customSections else {
                    // If the last section was custom, use the last custom section's after-ID,
                    // otherwise just use the last section ID
                    val afterSectionId = if (index == 0) 0 else sections[index - 1].let { (prevSectionId, _) ->
                        if (prevSectionId == 0) customSections.last().afterSectionId else prevSectionId
                    }
                    customSections + toCustomSection(b, afterSectionId)
                }
            }
        )
    }

    fun toResizableLimits(b: ByteReader) = b.readVarUInt1("flags").let {
        Node.ResizableLimits(
            initial = b.readVarUInt32AsInt("initial"),
            maximum = if (it) b.readVarUInt32AsInt("maximum") else null
        )
    }

    fun toTableType(b: ByteReader) = Node.Type.Table(toElemType(b), toResizableLimits(b))

    fun toValueType(b: ByteReader) = toValueType(b, b.readVarInt7("value_type").toInt())
    fun toValueType(b: ByteReader, type: Int) = when (type) {
        -0x01 -> Node.Type.Value.I32
        -0x02 -> Node.Type.Value.I64
        -0x03 -> Node.Type.Value.F32
        -0x04 -> Node.Type.Value.F64
        else -> error("Unknown value type")
    }

    fun ByteReader.readString() = this.readVarUInt32AsInt("len").let { String(this.readBytes("str", it)) }
    fun <T> ByteReader.readList(fn: (ByteReader) -> T) = (0 until this.readVarUInt32("count")).map { _ -> fn(this) }
    fun ByteReader.readVarUInt32AsInt(field: String) = this.readVarUInt32(field).toIntExact()

    companion object : BinaryToAst()
}