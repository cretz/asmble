package asmble.io

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ByteIoTest {
    @Test
    fun testBufferCommon() {
        val writer = ByteWriter.Buffer(ByteBuffer.allocate(1000))
        val reader = ByteReader.Buffer(writer.buf.duplicate())
        testReaderWriter(reader, writer)
    }

    fun testReaderWriter(reader: ByteReader, writer: ByteWriter) {
        writer.writeByte( -0x10)
        writer.writeByte(0x10)
        assertEquals(-0x10, reader.readByte())
        assertEquals(0x10, reader.readByte())
        assertEquals(2, writer.index)

        writer.writeBytes(byteArrayOf(0x20, -0x20))
        assertArrayEquals(byteArrayOf(0x20, -0x20), reader.readBytes(2))

        writer.writeUInt32(Int.MAX_VALUE.toLong() + 1)
        assertEquals(Int.MAX_VALUE.toLong() + 1, reader.readUInt32())
        assertFails { writer.writeUInt32(-1) }

        writer.writeUInt64(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, reader.readUInt64())
        assertFails { writer.writeUInt64(BigInteger.valueOf(-1)) }
    }
}