package asmble.io

import asmble.util.toIntExact
import asmble.util.toUnsignedBigInt
import asmble.util.toUnsignedLong
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ByteReader {
    val isEof: Boolean

    fun readByte(): Byte
    fun readBytes(amount: Int? = null): ByteArray
    fun readUInt32(): Long
    fun readUInt64(): BigInteger
    fun readVarInt7(): Byte
    fun readVarInt32(): Int
    fun readVarInt64(): Long
    fun readVarUInt1(): Boolean
    fun readVarUInt7(): Short
    fun readVarUInt32(): Long
    fun slice(amount: Int): ByteReader

    class Buffer(val buf: ByteBuffer) : ByteReader {
        init { buf.order(ByteOrder.LITTLE_ENDIAN) }

        override val isEof get() = buf.position() == buf.limit()

        override fun readByte() = buf.get()

        override fun readBytes(amount: Int?) =
            ByteArray(amount ?: buf.limit() - buf.position()).also { buf.get(it) }

        override fun readUInt32() = buf.getInt().toUnsignedLong()

        override fun readUInt64() = buf.getLong().toUnsignedBigInt()

        override fun readVarInt7() = readSignedLeb128().let {
            require(it >= Byte.MIN_VALUE.toLong() && it <= Byte.MAX_VALUE.toLong())
            it.toByte()
        }

        override fun readVarInt32() = readSignedLeb128().toIntExact()

        override fun readVarInt64() = readSignedLeb128()

        override fun readVarUInt1() = readUnsignedLeb128().let {
            require(it == 1 || it == 0)
            it == 1
        }

        override fun readVarUInt7() = readUnsignedLeb128().let {
            require(it <= 255)
            it.toShort()
        }

        override fun readVarUInt32() = readUnsignedLeb128().toUnsignedLong()

        override fun slice(amount: Int) = ByteReader.Buffer(buf.slice().also { it.limit(amount) })

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