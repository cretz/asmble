package asmble.io

import asmble.ast.Node

open class AstToBinary(val version: Long = 0xd) {

    fun fromCustomSection(b: ByteWriter, n: Node.CustomSection) {
        b.writeVarUInt7("id", 0)
        val payloadLenIndex = b.index
        b.writeVarUInt32("payload_len", 0)
        val mark = b.index
        val nameBytes = n.name.toByteArray()
        b.writeVarUInt32("name_len", nameBytes.size.toLong())
        b.writeBytes("name", nameBytes)
        b.writeBytes("payload_data", n.payload)
        // Go back and write payload
        b.writeVarUInt32("payload_len", (b.index - mark).toLong(), payloadLenIndex)
    }

    fun fromFuncType(b: ByteWriter, n: Node.Type.Func) {
        b.writeVarInt7("form", -0x20)
        b.writeVarUInt32("param_count", n.params.size.toLong())
        n.params.forEach { b.writeVarInt7("param_types", it.valueType) }
        b.writeVarUInt1("return_count", n.ret != null)
        n.ret?.let { b.writeVarInt7("return_type", it.valueType) }
    }

    fun fromGlobalType(b: ByteWriter, n: Node.Type.Global) {
        b.writeVarInt7("content_type", n.contentType.valueType)
        b.writeVarUInt1("mutability", n.mutable)
    }

    fun fromImport(b: ByteWriter, import: Node.Import) {
        val moduleBytes = import.module.toByteArray()
        b.writeVarUInt32("module_len", moduleBytes.size.toLong())
        b.writeBytes("module_str", moduleBytes)
        val fieldBytes = import.field.toByteArray()
        b.writeVarUInt32("field_len", fieldBytes.size.toLong())
        b.writeBytes("field_str", fieldBytes)
        b.writeByte("kind", import.kind.externalKind)
        when (import.kind) {
            is Node.Import.Kind.Func -> b.writeVarUInt32("type", import.kind.typeIndex.toLong())
            is Node.Import.Kind.Table -> fromTableType(b, import.kind.type)
            is Node.Import.Kind.Memory -> fromMemoryType(b, import.kind.type)
            is Node.Import.Kind.Global -> fromGlobalType(b, import.kind.type)
        }
    }

    fun <T> fromListSection(b: ByteWriter, n: List<T>, fn: (ByteWriter, T) -> Unit) {
        b.writeVarUInt32("count", n.size.toLong())
        n.forEach { fn(b, it) }
    }

    fun fromMemoryType(b: ByteWriter, n: Node.Type.Memory) {
        fromResizableLimits(b, n.limits)
    }

    fun fromModule(b: ByteWriter, n: Node.Module) {
        b.writeUInt32("magic_number", 0x6d736100)
        b.writeUInt32("version", version)
        // Sections
        wrapListSection(b, n, 1, n.types, this::fromFuncType)
        wrapListSection(b, n, 2, n.imports, this::fromImport)
        TODO("The rest...")
    }

    fun fromResizableLimits(b: ByteWriter, n: Node.ResizableLimits) {
        b.writeVarUInt1("flags", n.maximum != null)
        b.writeVarUInt32("initial", n.initial.toLong())
        n.maximum?.let { b.writeVarUInt32("maximum", it.toLong()) }
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
        // Check for any custom section we need to write here
        mod.customSections.filter { it.beforeSectionId == sectionId.toInt() }.forEach { fromCustomSection(b, it) }
        // Apply section
        b.writeVarUInt7("id", sectionId)
        val payloadLenIndex = b.index
        b.writeVarUInt32("payload_len", 0)
        val mark = b.index
        handler()
        // Go back and write payload
        b.writeVarUInt32("payload_len", (b.index - mark).toLong(), payloadLenIndex)
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