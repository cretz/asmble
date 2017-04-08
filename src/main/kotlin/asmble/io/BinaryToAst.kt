package asmble.io

import asmble.ast.Node
import asmble.util.toIntExact

open class BinaryToAst(val version: Long = 0xd) {

    fun toCustomSection(b: ByteReader, afterSectionId: Int) = Node.CustomSection(
        afterSectionId = afterSectionId,
        name = b.readString(),
        payload = b.readBytes("payload_data", b.size - b.index)
    )

    fun toElemType(b: ByteReader) = b.readVarInt7("elem_type").toInt().let {
        when (it) {
            -0x10 -> Node.ElemType.ANYFUNC
            else -> error("Unrecognized elem type: $it")
        }
    }

    fun toFuncType(b: ByteReader): Node.Type.Func {
        require(b.readVarInt7("form").toInt() == -0x20)
        return Node.Type.Func(
            params = b.readList(this::toValueType),
            ret = if (b.readVarUInt1("return_count")) toValueType(b) else null
        )
    }

    fun toGlobalType(b: ByteReader) = Node.Type.Global(
        contentType = toValueType(b),
        mutable = b.readVarUInt1("mutability")
    )

    fun toImport(b: ByteReader) = Node.Import(
        module = b.readString(),
        field = b.readString(),
        kind = b.readByte("external_kind").toInt().let {
            when (it) {
                0 -> Node.Import.Kind.Func(b.readVarUInt32("type").toIntExact())
                1 -> Node.Import.Kind.Table(toTableType(b))
                2 -> Node.Import.Kind.Memory(toMemoryType(b))
                3 -> Node.Import.Kind.Global(toGlobalType(b))
                else -> error("Unrecognized import kind: $it")
            }
        }
    )

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
            sections += sectionId to b.slice("payload_data", b.readVarUInt32("payload_len").toIntExact())
        }

        // Now build the module
        fun <T> readSectionList(sectionId: Int, fn: (ByteReader) -> T) =
            sections.find { it.first == sectionId }?.second?.readList(fn) ?: emptyList()
        val types = readSectionList(1, this::toFuncType)
        return Node.Module(
            types = types,
            imports = readSectionList(2, this::toImport),
            tables = TODO("Keep going..."),
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
            initial = b.readVarUInt32("initial").toIntExact(),
            maximum = if (it) b.readVarUInt32("maximum").toIntExact() else null
        )
    }

    fun toTableType(b: ByteReader) = Node.Type.Table(toElemType(b), toResizableLimits(b))

    fun toValueType(b: ByteReader) = when (b.readVarInt7("value_type").toInt()) {
        -0x01 -> Node.Type.Value.I32
        -0x02 -> Node.Type.Value.I64
        -0x03 -> Node.Type.Value.F32
        -0x04 -> Node.Type.Value.F64
        else -> error("Unknown value type")
    }

    fun ByteReader.readString() = this.readVarUInt32("len").toIntExact().let { String(this.readBytes("str", it)) }
    fun <T> ByteReader.readList(fn: (ByteReader) -> T) = (0 until this.readVarUInt32("count")).map { _ -> fn(this) }

    companion object : BinaryToAst()
}