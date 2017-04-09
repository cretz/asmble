package asmble.io

import asmble.util.unsignedToSignedInt
import asmble.util.unsignedToSignedLong
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ByteWriter {
    val index: Int

    fun writeByte(v: Byte, index: Int? = null)
    fun writeBytes(v: ByteArray, index: Int? = null)
    fun writeUInt32(v: Long, index: Int? = null)
    fun writeUInt64(v:  BigInteger, index: Int? = null)
    fun writeVarInt7(v: Byte, index: Int? = null)
    fun writeVarInt32(v: Int, index: Int? = null)
    fun writeVarInt64(v: Long, index: Int? = null)
    fun writeVarUInt1(v: Boolean, index: Int? = null)
    fun writeVarUInt7(v: Short, index: Int? = null)
    fun writeVarUInt32(v: Long, index: Int? = null)

    class Buffer(val buf: ByteBuffer) : ByteWriter {
        init { buf.order(ByteOrder.LITTLE_ENDIAN) }

        override val index get() = buf.position()

        override fun writeByte(v: Byte, index: Int?) {
            if (index == null) buf.put(v) else buf.put(index, v)
        }

        override fun writeBytes(v: ByteArray, index: Int?) {
            if (index == null) buf.put(v)
            else v.forEachIndexed { byteIndex, byte -> buf.put(index + byteIndex, byte) }
        }

        override fun writeUInt32(v: Long, index: Int?) {
            v.unsignedToSignedInt().also { if (index == null) buf.putInt(it) else buf.putInt(index, it) }
        }

        override fun writeUInt64(v: BigInteger, index: Int?) {
            v.unsignedToSignedLong().also { if (index == null) buf.putLong(it) else buf.putLong(index, it) }
        }

        override fun writeVarInt7(v: Byte, index: Int?) {
            writeSignedLeb128(v.toLong(), index)
        }

        override fun writeVarInt32(v: Int, index: Int?) {
            writeSignedLeb128(v.toLong(), index)
        }

        override fun writeVarInt64(v: Long, index: Int?) {
            writeSignedLeb128(v, index)
        }

        override fun writeVarUInt1(v: Boolean, index: Int?) {
            writeUnsignedLeb128(if (v) 1 else 0, index)
        }

        override fun writeVarUInt7(v: Short, index: Int?) {
            writeUnsignedLeb128(v.toLong().unsignedToSignedInt(), index)
        }

        override fun writeVarUInt32(v: Long, index: Int?) {
            writeUnsignedLeb128(v.unsignedToSignedInt(), index)
        }

        private fun writeUnsignedLeb128(v: Int, index: Int?) {
            // Taken from Android source, Apache licensed
            var index = index
            var v = v
            var remaining = v ushr 7
            while (remaining != 0) {
                val byte = ((v and 0x7f) or 0x80).toByte()
                if (index == null) buf.put(byte) else buf.put(index, byte).also { index++ }
                v = remaining
                remaining = remaining ushr 7
            }
            val byte = (v and 0x7f).toByte()
            if (index == null) buf.put(byte) else buf.put(index, byte)
        }

        private fun writeSignedLeb128(v: Long, index: Int?) {
            // Taken from Android source, Apache licensed
            var index = index
            var v = v
            var remaining = v shr 7
            var hasMore = true
            val end = if (v and Long.MAX_VALUE == 0L) 0L else -1L
            while (hasMore) {
                hasMore = remaining != end || remaining and 1 != (v shr 6) and 1
                val byte = ((v and 0x7f) or if (hasMore) 0x80 else 0).toByte()
                if (index == null) buf.put(byte) else buf.put(index, byte).also { index++ }
                v = remaining
                remaining = remaining shr 7
            }
        }
    }
}