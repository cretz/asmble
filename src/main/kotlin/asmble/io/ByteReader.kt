package asmble.io

import asmble.util.toIntExact
import asmble.util.toUnsignedBigInt
import asmble.util.toUnsignedLong
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ByteReader {
    val isEof: Boolean

    fun readByte(field: String): Byte
    fun readBytes(field: String, amount: Int? = null): ByteArray
    fun readUInt32(field: String): Long
    fun readUInt64(field: String): BigInteger
    fun readVarInt7(field: String): Byte
    fun readVarInt32(field: String): Int
    fun readVarInt64(field: String): Long
    fun readVarUInt1(field: String): Boolean
    fun readVarUInt7(field: String): Short
    fun readVarUInt32(field: String): Long
    fun slice(field: String, amount: Int): ByteReader

    class Buffer(val buf: ByteBuffer) : ByteReader {
        init { buf.order(ByteOrder.LITTLE_ENDIAN) }

        override val isEof get() = buf.position() == buf.limit()

        override fun readByte(field: String) = buf.get()

        override fun readBytes(field: String, amount: Int?) =
            ByteArray(amount ?: buf.limit() - buf.position()).also { buf.get(it) }

        override fun readUInt32(field: String) = buf.getInt().toUnsignedLong()

        override fun readUInt64(field: String) = buf.getLong().toUnsignedBigInt()

        override fun readVarInt7(field: String) = readSignedLeb128().let {
            require(it >= Byte.MIN_VALUE.toLong() && it <= Byte.MAX_VALUE.toLong())
            it.toByte()
        }

        override fun readVarInt32(field: String) = readSignedLeb128().toIntExact()

        override fun readVarInt64(field: String) = readSignedLeb128()

        override fun readVarUInt1(field: String) = readUnsignedLeb128().let {
            require(it == 1 || it == 0)
            it == 1
        }

        override fun readVarUInt7(field: String) = readUnsignedLeb128().let {
            require(it <= 255)
            it.toShort()
        }

        override fun readVarUInt32(field: String) = readUnsignedLeb128().toUnsignedLong()

        override fun slice(field: String, amount: Int) = ByteReader.Buffer(buf.slice().also { it.limit(amount) })

        private fun readUnsignedLeb128(): Int {
            // Taken from Android source, Apache licensed
            var result = 0
            var cur = 0
            var count = 0
            do {
                cur = buf.get().toInt() and 0xff
                result = result or ((cur and 0x7f) shl (count * 7))
                count++
            } while (cur and 0x80 == 0x80 && count < 5)
            if (cur and 0x80 == 0x80) throw NumberFormatException()
            return result
        }

        private fun readSignedLeb128(): Long {
            // Taken from Android source, Apache licensed
            var result = 0L
            var cur = 0
            var count = 0
            var signBits = -1L
            do {
                cur = buf.get().toInt() and 0xff
                result = result or ((cur and 0x7f).toLong() shl (count * 7))
                signBits = signBits shl 7
                count++
            } while (cur and 0x80 == 0x80 && count < 5)
            if (cur and 0x80 == 0x80) throw NumberFormatException()
            if ((signBits shr 1) and result != 0L) result = result or signBits
            return result
        }
    }
}