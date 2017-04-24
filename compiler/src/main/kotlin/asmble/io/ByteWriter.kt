package asmble.io

import asmble.util.unsignedToSignedInt
import asmble.util.unsignedToSignedLong
import java.io.ByteArrayOutputStream
import java.math.BigInteger

abstract class ByteWriter {
    abstract val written: Int

    // Parameter will have been created via createTemp
    abstract fun write(v: ByteWriter)
    abstract fun writeByte(v: Byte)
    abstract fun writeBytes(v: ByteArray)
    abstract fun createTemp(): ByteWriter

    fun writeUInt32(v: Long) {
        v.unsignedToSignedInt().also {
            writeByte(it.toByte())
            writeByte((it shr 8).toByte())
            writeByte((it shr 16).toByte())
            writeByte((it shr 24).toByte())
        }
    }

    fun writeUInt64(v: BigInteger) {
        v.unsignedToSignedLong().also {
            writeByte(it.toByte())
            writeByte((it shr 8).toByte())
            writeByte((it shr 16).toByte())
            writeByte((it shr 24).toByte())
            writeByte((it shr 32).toByte())
            writeByte((it shr 40).toByte())
            writeByte((it shr 48).toByte())
            writeByte((it shr 56).toByte())
        }
    }

    fun writeVarInt7(v: Byte) {
        writeSignedLeb128(v.toLong())
    }

    fun writeVarInt32(v: Int) {
        writeSignedLeb128(v.toLong())
    }

    fun writeVarInt64(v: Long) {
        writeSignedLeb128(v)
    }

    fun writeVarUInt1(v: Boolean) {
        writeUnsignedLeb128(if (v) 1 else 0)
    }

    fun writeVarUInt7(v: Short) {
        writeUnsignedLeb128(v.toLong().unsignedToSignedInt())
    }

    fun writeVarUInt32(v: Long) {
        writeUnsignedLeb128(v.unsignedToSignedInt())
    }

    protected fun writeUnsignedLeb128(v: Int) {
        // Taken from Android source, Apache licensed
        var v = v
        var remaining = v ushr 7
        while (remaining != 0) {
            val byte = (v and 0x7f) or 0x80
            writeByte(byte.toByte())
            v = remaining
            remaining = remaining ushr 7
        }
        val byte = v and 0x7f
        writeByte(byte.toByte())
    }

    protected fun writeSignedLeb128(v: Long) {
        // Taken from Android source, Apache licensed
        var v = v
        var remaining = v shr 7
        var hasMore = true
        val end = if (v and Long.MIN_VALUE == 0L) 0L else -1L
        while (hasMore) {
            hasMore = remaining != end || remaining and 1 != (v shr 6) and 1
            val byte = ((v and 0x7f) or if (hasMore) 0x80 else 0).toInt()
            writeByte(byte.toByte())
            v = remaining
            remaining = remaining shr 7
        }
    }

    class OutputStream(val os: java.io.OutputStream) : ByteWriter() {
        override var written = 0; private set

        override fun write(v: ByteWriter) {
            if (v !is OutputStream || v.os !is ByteArrayOutputStream) error("Writer not created from createTemp")
            v.os.writeTo(os)
            written += v.os.size()
        }

        override fun writeByte(v: Byte) {
            os.write(v.toInt())
            written++
        }

        override fun writeBytes(v: ByteArray) {
            os.write(v)
            written += v.size
        }

        override fun createTemp() = OutputStream(ByteArrayOutputStream())
    }
}