package asmble.io

import asmble.util.toUnsignedBigInt
import asmble.util.toUnsignedLong
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger


abstract class ByteReader {
    abstract val isEof: Boolean

    // Slices the next set off as its own and moves the position up that much
    abstract fun read(amount: Int): ByteReader
    abstract fun readByte(): Byte
    abstract fun readBytes(amount: Int? = null): ByteArray

    fun readUInt32(): Long {
        return ((readByte().toInt() and 0xff) or
            ((readByte().toInt() and 0xff) shl 8) or
            ((readByte().toInt() and 0xff) shl 16) or
            ((readByte().toInt() and 0xff) shl 24)).toUnsignedLong()
    }

    fun readUInt64(): BigInteger {
        return ((readByte().toLong() and 0xff) or
            ((readByte().toLong() and 0xff) shl 8) or
            ((readByte().toLong() and 0xff) shl 16) or
            ((readByte().toLong() and 0xff) shl 24) or
            ((readByte().toLong() and 0xff) shl 32) or
            ((readByte().toLong() and 0xff) shl 40) or
            ((readByte().toLong() and 0xff) shl 48) or
            ((readByte().toLong() and 0xff) shl 56)).toUnsignedBigInt()
    }

    fun readVarInt7() = readSignedLeb128().let {
        if (it < Byte.MIN_VALUE.toLong() || it > Byte.MAX_VALUE.toLong()) throw IoErr.InvalidLeb128Number()
        it.toByte()
    }

    fun readVarInt32() = readSignedLeb128().let {
        if (it < Int.MIN_VALUE.toLong() || it > Int.MAX_VALUE.toLong()) throw IoErr.InvalidLeb128Number()
        it.toInt()
    }

    fun readVarInt64() = readSignedLeb128(9)

    fun readVarUInt1() = readUnsignedLeb128().let {
        if (it != 1 && it != 0) throw IoErr.InvalidLeb128Number()
        it == 1
    }

    fun readVarUInt7() = readUnsignedLeb128().let {
        if (it > 255) throw IoErr.InvalidLeb128Number()
        it.toShort()
    }

    fun readVarUInt32() = readUnsignedLeb128().toUnsignedLong()

    protected fun readUnsignedLeb128(maxCount: Int = 4): Int {
        // Taken from Android source, Apache licensed
        var result = 0
        var cur: Int
        var count = 0
        do {
            cur = readByte().toInt() and 0xff
            result = result or ((cur and 0x7f) shl (count * 7))
            count++
        } while (cur and 0x80 == 0x80 && count <= maxCount)
        if (cur and 0x80 == 0x80) throw IoErr.InvalidLeb128Number()
        // Result can't have used more than ceil(result/7)
        if (cur != 0 && count - 1 > (result.toUnsignedLong() + 6) / 7) throw IoErr.InvalidLeb128Number()
        return result
    }

    private fun readSignedLeb128(maxCount: Int = 4): Long {
        // Taken from Android source, Apache licensed
        var result = 0L
        var cur: Int
        var count = 0
        var signBits = -1L
        do {
            cur = readByte().toInt() and 0xff
            result = result or ((cur and 0x7f).toLong() shl (count * 7))
            signBits = signBits shl 7
            count++
        } while (cur and 0x80 == 0x80 && count <= maxCount)
        if (cur and 0x80 == 0x80) throw IoErr.InvalidLeb128Number()

        // Check for 64 bit invalid, taken from Apache/MIT licensed:
        //  https://github.com/paritytech/parity-wasm/blob/2650fc14c458c6a252c9dc43dd8e0b14b6d264ff/src/elements/primitives.rs#L351
        // TODO: probably need 32 bit checks too, but meh, not in the suite
        if (count > maxCount && maxCount == 9) {
            if (cur and 0b0100_0000 == 0b0100_0000) {
                if ((cur or 0b1000_0000).toByte() != (-1).toByte()) throw IoErr.InvalidLeb128Number()
            } else if (cur != 0) {
                throw IoErr.InvalidLeb128Number()
            }
        }

        if ((signBits shr 1) and result != 0L) result = result or signBits
        return result
    }

    class InputStream(val ins: java.io.InputStream) : ByteReader() {
        private var nextByte: Byte? = null
        private var sawEof = false
        override val isEof: Boolean get() {
            if (!sawEof && nextByte == null) {
                val b = ins.read()
                if (b == -1) sawEof = true else nextByte = b.toByte()
            }
            return sawEof && nextByte == null
        }

        override fun read(amount: Int) = ByteReader.InputStream(ByteArrayInputStream(readBytes(amount)))

        override fun readByte(): Byte {
            nextByte?.let { nextByte = null; return it }
            val b = ins.read()
            if (b >= 0) return b.toByte()
            sawEof = true
            throw IoErr.UnexpectedEnd()
        }

        override fun readBytes(amount: Int?): ByteArray {
            // If we have the amount, we create a byte array for reading that
            // otherwise we read until the end
            if (amount != null) {
                val ret = ByteArray(amount)
                var remaining = amount
                if (nextByte != null) {
                    ret[0] = nextByte!!
                    nextByte = null
                    remaining--
                }
                while (remaining > 0) {
                    val off = amount - remaining
                    val read = ins.read(ret, off, remaining)
                    if (read == -1) {
                        sawEof = true
                        throw IoErr.UnexpectedEnd()
                    }
                    remaining -= read
                }
                return ret
            } else {
                val out = ByteArrayOutputStream()
                if (nextByte != null) {
                    out.write(nextByte!!.toInt())
                    nextByte = null
                }
                val buf = ByteArray(8192)
                while (true) {
                    val r = ins.read(buf)
                    if (r == -1) break
                    out.write(buf, 0, r)
                }
                sawEof = true
                return out.toByteArray()
            }
        }
    }
}