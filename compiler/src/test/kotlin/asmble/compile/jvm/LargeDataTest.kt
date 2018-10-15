package asmble.compile.jvm

import asmble.TestBase
import asmble.ast.Node
import asmble.run.jvm.ModuleBuilder
import asmble.util.get
import org.junit.Test
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.assertEquals

class LargeDataTest : TestBase() {
    @Test
    fun testLargeData() {
        // This previously failed because string constants can't be longer than 65536 chars.
        // We create a byte array across the whole gambit of bytes to test UTF8 encoding.
        val bytesExpected = ByteArray(70000) { ((it % 255) - Byte.MIN_VALUE).toByte() }
        val mod = Node.Module(
            memories = listOf(Node.Type.Memory(
                limits = Node.ResizableLimits(initial = 2, maximum = 2)
            )),
            data = listOf(Node.Data(
                index = 0,
                offset = listOf(Node.Instr.I32Const(0)),
                data = bytesExpected
            ))
        )
        val ctx = ClsContext(
            packageName = "test",
            className = "Temp" + UUID.randomUUID().toString().replace("-", ""),
            mod = mod,
            logger = logger
        )
        AstToAsm.fromModule(ctx)
        val cls = ModuleBuilder.Compiled.SimpleClassLoader(javaClass.classLoader, logger).fromBuiltContext(ctx)
        // Instantiate it, get the memory out, and check it
        val field = cls.getDeclaredField("memory").apply { isAccessible = true }
        val buf = field[cls.newInstance()] as ByteBuffer
        // Grab all + 1 and check values
        val bytesActual = ByteArray(70001).also { buf.get(0, it) }
        bytesActual.forEachIndexed { index, byte ->
            assertEquals(if (index == 70000) 0.toByte() else bytesExpected[index], byte)
        }
    }
}