package asmble.ast.opt

import asmble.TestBase
import asmble.ast.Node
import asmble.compile.jvm.AstToAsm
import asmble.compile.jvm.ClsContext
import asmble.run.jvm.ScriptContext
import org.junit.Test
import java.nio.ByteBuffer
import java.util.*
import kotlin.test.assertEquals

class SplitLargeFuncTest : TestBase() {
    @Test
    fun testSplitLargeFunc() {
        // We're going to make a large function that does some addition and then stores in mem
        val ctx = ClsContext(
            packageName = "test",
            className = "Temp" + UUID.randomUUID().toString().replace("-", ""),
            logger = logger,
            mod = Node.Module(
                memories = listOf(Node.Type.Memory(Node.ResizableLimits(initial = 2, maximum = 2))),
                funcs = listOf(Node.Func(
                    type = Node.Type.Func(params = emptyList(), ret = null),
                    locals = emptyList(),
                    instructions = (0 until 500).flatMap {
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
        val unsplitCls = ScriptContext.SimpleClassLoader(javaClass.classLoader, logger).fromBuiltContext(ctx)
        val unsplitInst = unsplitCls.newInstance()
        // Run someFunc
        unsplitCls.getMethod("someFunc").invoke(unsplitInst)
        // Get the memory out
        val unsplitMem = unsplitCls.getMethod("getMemory").invoke(unsplitInst) as ByteBuffer
        // Read out the mem values
        (0 until 500).forEach { assertEquals(it * (it - 1), unsplitMem.getInt(it * 4)) }
        // Now split it
        val (splitMod, insnsSaved) = SplitLargeFunc.apply(ctx.mod, 0) ?: error("Nothing could be split")
        println("SAVED! $insnsSaved NEW FUNC - " + splitMod.funcs.last())
        // TODO: the rest
    }
}