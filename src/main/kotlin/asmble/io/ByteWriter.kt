package asmble.io

import asmble.util.unsignedToSignedInt
import asmble.util.unsignedToSignedLong
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ByteWriter {
    val index: Int

    fun writeByte(field: String, v: Byte, index: Int = index)
    fun writeBytes(field: String, v: ByteArray, index: Int = index)
    fun writeUInt32(field: String, v: Long, index: Int = index)
    fun writeUInt64(field: String, v:  BigInteger, index: Int = index)
    fun writeVarInt7(field: String, v: Byte, index: Int = index)
    fun writeVarInt32(field: String, v: Int, index: Int = index)
    fun writeVarInt64(field: String, v: Long, index: Int = index)
    fun writeVarUInt1(field: String, v: Boolean, index: Int = index)
    fun writeVarUInt7(field: String, v: Short, index: Int = index)
    fun writeVarUInt32(field: String, v: Long, index: Int = index)

    class Buffer(val buf: ByteBuffer) : ByteWriter {
        init { buf.order(ByteOrder.LITTLE_ENDIAN) }

        override val index get() = buf.position()

        override fun writeByte(field: String, v: Byte, index: Int) {
            buf.put(index, v)
        }

        override fun writeBytes(field: String, v: ByteArray, index: Int) {
            if (index == this.index) buf.put(v)
            else v.forEachIndexed { byteIndex, byte -> buf.put(index + byteIndex, byte) }
        }

        override fun writeUInt32(field: String, v: Long, index: Int) {
            buf.putInt(index, v.unsignedToSignedInt())
        }

        override fun writeUInt64(field: String, v: BigInteger, index: Int) {
            buf.putLong(index, v.unsignedToSignedLong())
        }

        override fun writeVarInt7(field: String, v: Byte, index: Int) {
            writeSignedLeb128(v.toLong(), index)
        }

        override fun writeVarInt32(field: String, v: Int, index: Int) {
            writeSignedLeb128(v.toLong(), index)
        }

        override fun writeVarInt64(field: String, v: Long, index: Int) {
            writeSignedLeb128(v, index)
        }

        override fun writeVarUInt1(field: String, v: Boolean, index: Int) {
            writeUnsignedLeb128(if (v) 1 else 0, index)
        }

        override fun writeVarUInt7(field: String, v: Short, index: Int) {
            writeUnsignedLeb128(v.toLong().unsignedToSignedInt(), index)
        }

        override fun writeVarUInt32(field: String, v: Long, index: Int) {
            writeUnsignedLeb128(v.unsignedToSignedInt(), index)
        }

        private fun writeUnsignedLeb128(v: Int, index: Int) {
            // Taken from Android source, Apache licensed
            var index = index
            var v = v
            var remaining = v ushr 7
            while (remaining != 0) {
                buf.put(index, ((v and 0x7f) or 0x80).toByte())
                index++
                v = remaining
                remaining = remaining ushr 7
            }
            buf.put(index, (v and 0x7f).toByte())
        }

        private fun writeSignedLeb128(v: Long, index: Int) {
            // Taken from Android source, Apache licensed
            var index = index
            var v = v
            var remaining = v shr 7
            var hasMore = true
            val end = if (v and Long.MAX_VALUE == 0L) 0L else -1L
            while (hasMore) {
                hasMore = remaining != end || remaining and 1 != (v shr 6) and 1
                buf.put(index, ((v and 0x7f) or if (hasMore) 0x80 else 0).toByte())
                index++
                v = remaining
                remaining = remaining shr 7
            }
        }
    }
}