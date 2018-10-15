package asmble.run.jvm

import asmble.TestBase
import asmble.ast.Node
import asmble.compile.jvm.AstToAsm
import asmble.compile.jvm.ClsContext
import org.junit.Assert
import org.junit.Test
import org.objectweb.asm.MethodTooLargeException
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.assertEquals

class LargeFuncTest : TestBase() {
    @Test
    fun testLargeFunc() {
        val numInsnChunks = 6001
        // Make large func that does some math
        val ctx = ClsContext(
            packageName = "test",
            className = "Temp" + UUID.randomUUID().toString().replace("-", ""),
            logger = logger,
            mod = Node.Module(
                memories = listOf(Node.Type.Memory(Node.ResizableLimits(initial = 4, maximum = 4))),
                funcs = listOf(Node.Func(
                    type = Node.Type.Func(params = emptyList(), ret = null),
                    locals = emptyList(),
                    instructions = (0 until numInsnChunks).flatMap {
                        listOf<Node.Instr>(
                            Node.Instr.I32Const(it * 4),
                            // Let's to i * (i = 1)
                            Node.Instr.I32Const(it),
                            Node.Instr.I32Const(it - 1),
                            Node.Instr.I32Mul,
                            Node.Instr.I32Store(0, 0)
                        )
                    }
                )),
                names = Node.NameSection(
                    moduleName = null,
                    funcNames = mapOf(0 to "someFunc"),
                    localNames = emptyMap()
                ),
                exports = listOf(
                    Node.Export("memory", Node.ExternalKind.MEMORY, 0),
                    Node.Export("someFunc", Node.ExternalKind.FUNCTION, 0)
                )
            )
        )
        // Compile it
        AstToAsm.fromModule(ctx)
        // Confirm the method size is too large
        try {
            ModuleBuilder.Compiled.SimpleClassLoader(javaClass.classLoader, logger, splitWhenTooLarge = false).
                fromBuiltContext(ctx)
            Assert.fail()
        } catch (e: MethodTooLargeException) { }
        // Try again with split
        val cls = ModuleBuilder.Compiled.SimpleClassLoader(javaClass.classLoader, logger).fromBuiltContext(ctx)
        // Create it and check that it still does what we expect
        val inst = cls.newInstance()
        // Run someFunc
        cls.getMethod("someFunc").invoke(inst)
        // Get the memory out
        val mem = cls.getMethod("getMemory").invoke(inst) as ByteBuffer
        // Read out the mem values
        (0 until numInsnChunks).forEach { assertEquals(it * (it - 1), mem.getInt(it * 4)) }
    }
}