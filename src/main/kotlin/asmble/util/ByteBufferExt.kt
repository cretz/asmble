package asmble.util

import java.nio.ByteBuffer

fun ByteBuffer.get(index: Int, bytes: ByteArray) = this.duplicate().also { it.position(index) }.get(bytes)