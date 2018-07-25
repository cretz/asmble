package asmble.io

import asmble.ast.Node
import asmble.util.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

open class BinaryToAst(
    val version: Long = 1L,
    val logger: Logger = Logger.Print(Logger.Level.OFF),
    val includeNameSection: Boolean = true
) : Logger by logger {

    fun toBlockType(b: ByteReader) = b.readVarInt7().toInt().let {
        if (it == -0x40) null else toValueType(it)
    }

    fun toCustomSection(b: ByteReader, afterSectionId: Int) = Node.CustomSection(
        afterSectionId = afterSectionId,
        name = b.readString(),
        payload = b.readBytes()
    )

    fun toNameSection(b: ByteReader) = generateSequence {
        if (b.isEof) null
        else b.readVarUInt7().toInt() to b.read(b.readVarUInt32AsInt())
    }.fold(Node.NameSection(null, emptyMap(), emptyMap())) { sect, (type, b) ->
        fun <T> indexMap(b: ByteReader, fn: (ByteReader) -> T) =
            b.readList { it.readVarUInt32AsInt() to fn(it) }.let { pairs ->
                pairs.toMap().also { require(it.size == pairs.size) { "Malformed names: duplicate indices" } }
            }
        fun nameMap(b: ByteReader) = indexMap(b) { it.readString() }
        when (type) {
            0 -> sect.copy(moduleName = b.readString())
            1 -> sect.copy(funcNames = nameMap(b))
            2 -> sect.copy(localNames = indexMap(b, ::nameMap))
            else -> error("Malformed names: unrecognized type: $type")
        }.also { require(b.isEof) }
    }

    fun toData(b: ByteReader) = Node.Data(
        index = b.readVarUInt32AsInt(),
        offset = toInitExpr(b),
        data = b.readVarUInt32AsInt().let { b.readBytes(it) }
    )

    fun toElem(b: ByteReader) = Node.Elem(
        index = b.readVarUInt32AsInt(),
        offset = toInitExpr(b),
        funcIndices = b.readList { it.readVarUInt32AsInt() }
    )

    fun toElemType(b: ByteReader) = b.readVarInt7().toInt().let {
        when (it) {
            -0x10 -> Node.ElemType.ANYFUNC
            else -> error("Unrecognized elem type: $it")
        }
    }

    fun toExport(b: ByteReader) = Node.Export(
        field = b.readString(),
        kind = b.readByte().toInt().let {
            when (it) {
                0 -> Node.ExternalKind.FUNCTION
                1 -> Node.ExternalKind.TABLE
                2 -> Node.ExternalKind.MEMORY
                3 -> Node.ExternalKind.GLOBAL
                else -> error("Unrecognized export kind: $it")
            }
        },
        index = b.readVarUInt32AsInt()
    )

    fun toFunc(b: ByteReader, type: Node.Type.Func) = b.read(b.readVarUInt32AsInt()).let { b ->
        Node.Func(
            type = type,
            locals = b.readList(this::toLocals).flatten(),
            instructions = toInstrs(b).let {
                require(it.lastOrNull() == Node.Instr.End) {
                    "Expected function to end with 'end', but got ${it.lastOrNull()}"
                }.run { it.dropLast(1) }
            }
        )
    }

    fun toFuncType(b: ByteReader): Node.Type.Func {
        require(b.readVarInt7().toInt() == -0x20)
        return Node.Type.Func(
            params = b.readList(this::toValueType),
            ret = if (b.readVarUInt1()) toValueType(b) else null
        )
    }

    fun toGlobal(b: ByteReader) = Node.Global(toGlobalType(b), toInitExpr(b))

    fun toGlobalType(b: ByteReader) = Node.Type.Global(
        contentType = toValueType(b),
        mutable = try { b.readVarUInt1() } catch (_: Exception) { throw IoErr.InvalidMutability() }
    )

    fun toImport(b: ByteReader) = Node.Import(
        module = b.readString(),
        field = b.readString(),
        kind = b.readByte().toInt().let {
            when (it) {
                0 -> Node.Import.Kind.Func(b.readVarUInt32AsInt())
                1 -> Node.Import.Kind.Table(toTableType(b))
                2 -> Node.Import.Kind.Memory(toMemoryType(b))
                3 -> Node.Import.Kind.Global(toGlobalType(b))
                else -> error("Unrecognized import kind: $it")
            }
        }
    )

    fun toInitExpr(b: ByteReader) = listOf(toInstr(b)).also { require(toInstr(b) == Node.Instr.End) }

    fun toInstrs(b: ByteReader) = mutableListOf<Node.Instr>().also { while (!b.isEof) it += toInstr(b) }.toList()

    fun toInstr(b: ByteReader) = Node.InstrOp.op(b.readByte().toUnsignedShort()).let { op ->
        when (op) {
            is Node.InstrOp.ControlFlowOp.NoArg ->
                op.create
            is Node.InstrOp.ControlFlowOp.TypeArg ->
                op.create(toBlockType(b))
            is Node.InstrOp.ControlFlowOp.DepthArg ->
                op.create(b.readVarUInt32AsInt())
            is Node.InstrOp.ControlFlowOp.TableArg -> op.create(
                b.readList { it.readVarUInt32AsInt() },
                b.readVarUInt32AsInt()
            )
            is Node.InstrOp.CallOp.IndexArg ->
                op.create(b.readVarUInt32AsInt())
            is Node.InstrOp.CallOp.IndexReservedArg -> op.create(
                b.readVarUInt32AsInt(),
                b.readVarUInt1().also { if (it) throw IoErr.InvalidReservedArg() }
            )
            is Node.InstrOp.ParamOp.NoArg ->
                op.create
            is Node.InstrOp.VarOp.IndexArg ->
                op.create(b.readVarUInt32AsInt())
            is Node.InstrOp.MemOp.AlignOffsetArg -> op.create(
                b.readVarUInt32AsInt(),
                b.readVarUInt32()
            )
            is Node.InstrOp.MemOp.ReservedArg ->
                op.create(b.readVarUInt1().also { if (it) throw IoErr.InvalidReservedArg() })
            is Node.InstrOp.ConstOp.IntArg ->
                op.create(b.readVarInt32())
            is Node.InstrOp.ConstOp.LongArg ->
                op.create(b.readVarInt64())
            is Node.InstrOp.ConstOp.FloatArg ->
                op.create(Float.fromIntBits(b.readUInt32().unsignedToSignedInt()))
            is Node.InstrOp.ConstOp.DoubleArg ->
                op.create(Double.fromLongBits(b.readUInt64().unsignedToSignedLong()))
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

    fun toLocals(b: ByteReader): List<Node.Type.Value> {
        val size = try { b.readVarUInt32AsInt() } catch (e: NumberFormatException) { throw IoErr.InvalidLocalSize(e) }
        return toValueType(b).let { type -> List(size) { type } }
    }

    fun toMemoryType(b: ByteReader) = Node.Type.Memory(toResizableLimits(b))

    fun toModule(b: ByteArray) = toModule(ByteReader.InputStream(b.inputStream()))

    fun toModule(b: ByteReader): Node.Module {
        if (b.readUInt32() != 0x6d736100L) throw IoErr.InvalidMagicNumber()
        b.readUInt32().let { if (it != version) throw IoErr.InvalidVersion(it, listOf(version)) }

        // Slice up all the sections
        var maxSectionId = 0
        var sections = emptyList<Pair<Int, ByteReader>>()
        while (!b.isEof) {
            val sectionId = b.readVarUInt7().toInt()
            if (sectionId > 11) throw IoErr.InvalidSectionId(sectionId)
            if (sectionId != 0)
                require(sectionId > maxSectionId) { "Section ID $sectionId came after $maxSectionId" }.
                    also { maxSectionId = sectionId }
            val sectionLen = b.readVarUInt32AsInt()
            sections += sectionId to b.read(sectionLen)
        }

        // Now build the module
        fun <T> readSectionList(sectionId: Int, fn: (ByteReader) -> T) =
            sections.find { it.first == sectionId }?.second?.readList(fn) ?: emptyList()
        val types = readSectionList(1, this::toFuncType)
        val funcIndices = readSectionList(3) { it.readVarUInt32AsInt() }
        var nameSection: Node.NameSection? = null
        return Node.Module(
            types = types,
            imports = readSectionList(2, this::toImport),
            tables = readSectionList(4, this::toTableType),
            memories = readSectionList(5, this::toMemoryType),
            globals = readSectionList(6, this::toGlobal),
            exports = readSectionList(7, this::toExport),
            startFuncIndex = sections.find { it.first == 8 }?.second?.readVarUInt32AsInt(),
            elems = readSectionList(9, this::toElem),
            funcs = readSectionList(10) { it }.
                also { if (it.size != funcIndices.size) throw IoErr.InvalidCodeLength(funcIndices.size, it.size) }.
                zip(funcIndices.map { types[it] }, this::toFunc),
            data = readSectionList(11, this::toData),
            customSections = sections.foldIndexed(emptyList()) { index, customSections, (sectionId, b) ->
                if (sectionId != 0) customSections else {
                    // If the last section was custom, use the last custom section's after-ID,
                    // otherwise just use the last section ID
                    val afterSectionId = if (index == 0) 0 else sections[index - 1].let { (prevSectionId, _) ->
                        if (prevSectionId == 0) customSections.last().afterSectionId else prevSectionId
                    }
                    // Try to parse the name section
                    val section = toCustomSection(b, afterSectionId).takeIf { section ->
                        val shouldParseNames = includeNameSection && nameSection == null && section.name == "name"
                        !shouldParseNames || try {
                            nameSection = toNameSection(ByteReader.InputStream(section.payload.inputStream()))
                            false
                        } catch (e: Exception) { warn { "Failed parsing name section: $e" }; true }
                    }
                    if (section == null) customSections else customSections + section
                }
            }
        ).copy(names = nameSection)
    }

    fun toResizableLimits(b: ByteReader) = b.readVarUInt1().let {
        Node.ResizableLimits(
            initial = b.readVarUInt32AsInt(),
            maximum = if (it) b.readVarUInt32AsInt() else null
        )
    }

    fun toTableType(b: ByteReader) = Node.Type.Table(toElemType(b), toResizableLimits(b))

    fun toValueType(b: ByteReader) = toValueType(b.readVarInt7().toInt())
    fun toValueType(type: Int) = when (type) {
        -0x01 -> Node.Type.Value.I32
        -0x02 -> Node.Type.Value.I64
        -0x03 -> Node.Type.Value.F32
        -0x04 -> Node.Type.Value.F64
        else -> error("Unknown value type: $type")
    }

    fun ByteReader.readString() = this.readVarUInt32AsInt().let {
        // We have to use the decoder directly to get malformed-input errors
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this.readBytes(it))).toString()
    }
    fun <T> ByteReader.readList(fn: (ByteReader) -> T) = this.readVarUInt32().let { listSize ->
        (0 until listSize).map { fn(this) }
    }
    fun ByteReader.readVarUInt32AsInt() = this.readVarUInt32().toIntExact()

    companion object : BinaryToAst()
}