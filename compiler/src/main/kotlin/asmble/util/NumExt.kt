package asmble.util

import java.math.BigDecimal
import java.math.BigInteger

internal const val INT_MASK = 0xffffffffL
internal val MAX_UINT32 = BigInteger("ffffffff", 16)
internal val MIN_INT32 = BigInteger.valueOf(Int.MIN_VALUE.toLong())
internal val MAX_UINT64 = BigInteger("ffffffffffffffff", 16)
internal val MIN_INT64 = BigInteger.valueOf(Long.MIN_VALUE)

fun Byte.toUnsignedInt() = java.lang.Byte.toUnsignedInt(this)
fun Byte.toUnsignedLong() = java.lang.Byte.toUnsignedLong(this)
fun Byte.toUnsignedShort() = (this.toInt() and 0xff).toShort()

fun BigInteger.unsignedToSignedLong(): Long {
    if (this.signum() < 0 || this.bitLength() > java.lang.Long.SIZE) throw NumberFormatException()
    return this.toLong()
}

fun Double.toRawLongBits() = java.lang.Double.doubleToRawLongBits(this)

fun Double.Companion.fromLongBits(v: Long) = java.lang.Double.longBitsToDouble(v)

fun Float.toRawIntBits() = java.lang.Float.floatToRawIntBits(this)

fun Float.Companion.fromIntBits(v: Int) = java.lang.Float.intBitsToFloat(v)

fun Int.toUnsignedLong() = java.lang.Integer.toUnsignedLong(this)

fun Long.toIntExact() =
    if (this > Int.MAX_VALUE.toLong() || this < Int.MIN_VALUE.toLong())
        throw NumberFormatException("Expected within int range, got $this")
    else this.toInt()

fun Long.toUnsignedBigInt() =
    if (this >= 0) BigInteger.valueOf(this and 0x7fffffffffffffffL)
    else BigInteger.valueOf(this and 0x7fffffffffffffffL).setBit(java.lang.Long.SIZE - 1)

fun Long.unsignedToSignedInt(): Int {
    if (this and INT_MASK != this) throw NumberFormatException()
    return this.toInt()
}

fun Long.Companion.valueOf(s: String, radix: Int = 10) = java.lang.Long.valueOf(s, radix)

fun Short.toUnsignedInt() = java.lang.Short.toUnsignedInt(this)
fun Short.toUnsignedLong() = java.lang.Short.toUnsignedLong(this)