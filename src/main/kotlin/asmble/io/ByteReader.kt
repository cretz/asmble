package asmble.io

import java.math.BigInteger

interface ByteReader {
    val isEof: Boolean
    val index: Int
    val size: Int

    fun readByte(field: String): Byte
    fun readBytes(field: String, amount: Int): ByteArray
    fun readUInt32(field: String): Long
    fun readUInt64(field: String): BigInteger
    fun readVarInt7(field: String): Byte
    fun readVarInt32(field: String): Int
    fun readVarInt64(field: String): Long
    fun readVarUInt1(field: String): Boolean
    fun readVarUInt7(field: String): Short
    fun readVarUInt32(field: String): Long
    fun slice(field: String, amount: Int): ByteReader
}