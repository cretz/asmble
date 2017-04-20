package asmble.io

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteIoTest {
    @Test
    fun testBufferCommon() {
        val ins = PipedInputStream()
        val out = PipedOutputStream(ins)
        val reader = ByteReader.InputStream(ins)
        val writer = ByteWriter.OutputStream(out)
        testReaderWriter(reader, writer)
    }

    fun testReaderWriter(reader: ByteReader, writer: ByteWriter) {
        writer.writeByte( -0x10)
        writer.writeByte(0x10)
        assertEquals(-0x10, reader.readByte())
        assertEquals(0x10, reader.readByte())
        assertEquals(2, writer.written)

        writer.writeBytes(byteArrayOf(0x20, -0x20))
        assertArrayEquals(byteArrayOf(0x20, -0x20), reader.readBytes(2))

        writer.writeUInt32(Int.MAX_VALUE.toLong() + 1)
        assertEquals(Int.MAX_VALUE.toLong() + 1, reader.readUInt32())
        assertFails { writer.writeUInt32(-1) }

        writer.writeUInt64(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, reader.readUInt64())
        assertFails { writer.writeUInt64(BigInteger.valueOf(-1)) }

        writer.writeVarInt7(0x30)
        writer.writeVarInt7(-0x30)
        assertEquals(0x30, reader.readVarInt7())
        assertEquals(-0x30, reader.readVarInt7())

        writer.writeVarInt32(Int.MAX_VALUE)
        writer.writeVarInt32(Int.MIN_VALUE)
        assertEquals(Int.MAX_VALUE, reader.readVarInt32())
        assertEquals(Int.MIN_VALUE, reader.readVarInt32())

        writer.writeVarInt64(Long.MAX_VALUE)
        writer.writeVarInt64(Long.MIN_VALUE)
        assertEquals(Long.MAX_VALUE, reader.readVarInt64())
        assertEquals(Long.MIN_VALUE, reader.readVarInt64())

        writer.writeVarUInt1(true)
        writer.writeVarUInt1(false)
        assertTrue(reader.readVarUInt1())
        assertFalse(reader.readVarUInt1())

        writer.writeVarUInt7(0x40)
        assertEquals(0x40, reader.readVarUInt7())
        assertFails { writer.writeVarUInt7(-1) }

        writer.writeVarUInt32(Int.MAX_VALUE * 2L - 1)
        assertEquals(Int.MAX_VALUE * 2L - 1, reader.readVarUInt32())
        assertFails { writer.writeVarUInt32(-1) }
    }
}