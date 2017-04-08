package asmble.util

import java.math.BigInteger

internal const val INT_MASK = 0xffffffffL

fun BigInteger.unsignedToSignedLong(): Long {
    if (this.signum() < 0 || this.bitLength() > java.lang.Long.SIZE) throw NumberFormatException()
    return this.toLong()
}

fun Double.toRawLongBits() = java.lang.Double.doubleToRawLongBits(this)

fun Double.Companion.fromLongBits(v: Long) = java.lang.Double.longBitsToDouble(v)

fun Float.toRawIntBits() = java.lang.Float.floatToRawIntBits(this)

fun Float.Companion.fromIntBits(v: Int) = java.lang.Float.intBitsToFloat(v)

fun Int.toUnsignedLong() = java.lang.Integer.toUnsignedLong(this)

fun Long.toUnsignedBigInt() =
    if (this >= 0) BigInteger.valueOf(this and 0x7fffffffffffffffL)
    else BigInteger.valueOf(this and 0x7fffffffffffffffL).setBit(java.lang.Long.SIZE - 1)

fun Long.unsignedToSignedInt(): Int {
    if (this and INT_MASK != this) throw NumberFormatException()
    return this.toInt()
}

fun Long.Companion.valueOf(s: String, radix: Int = 10) = java.lang.Long.valueOf(s, radix)