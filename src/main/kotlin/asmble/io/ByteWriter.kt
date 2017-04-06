package asmble.io

interface ByteWriter {
    val index: Int

    fun writeByte(field: String, v: Byte, index: Int = index)
    fun writeBytes(field: String, v: ByteArray, index: Int = index)
    fun writeUInt32(field: String, v: Long, index: Int = index)
    fun writeVarInt7(field: String, v: Byte, index: Int = index)
    fun writeVarUInt1(field: String, v: Boolean, index: Int = index)
    fun writeVarUInt7(field: String, v: Short, index: Int = index)
    fun writeVarUInt32(field: String, v: Long, index: Int = index)
}