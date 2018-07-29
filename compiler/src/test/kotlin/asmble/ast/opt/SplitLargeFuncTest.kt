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
                    instructions = (0 until 501).flatMap {
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
        val cls = ScriptContext.SimpleClassLoader(javaClass.classLoader, logger).fromBuiltContext(ctx)
        val inst = cls.newInstance()
        // Run someFunc
        cls.getMethod("someFunc").invoke(inst)
        // Get the memory out
        val mem = cls.getMethod("getMemory").invoke(inst) as ByteBuffer
        // Read out the mem values
        (0 until 501).forEach { assertEquals(it * (it - 1), mem.getInt(it * 4)) }

        // Now split it
        val (splitMod, insnsSaved) = SplitLargeFunc.apply(ctx.mod, 0) ?: error("Nothing could be split")
        // Count insns and confirm it is as expected
        val origInsnCount = ctx.mod.funcs.sumBy { it.instructions.size }
        val newInsnCount = splitMod.funcs.sumBy { it.instructions.size }
        assertEquals(origInsnCount - newInsnCount, insnsSaved)
        // Compile it
        val splitCtx = ClsContext(
            packageName = "test",
            className = "Temp" + UUID.randomUUID().toString().replace("-", ""),
            logger = logger,
            mod = splitMod
        )
        AstToAsm.fromModule(splitCtx)
        val splitCls = ScriptContext.SimpleClassLoader(javaClass.classLoader, logger).fromBuiltContext(splitCtx)
        val splitInst = splitCls.newInstance()
        // Run someFunc
        splitCls.getMethod("someFunc").invoke(splitInst)
        // Get the memory out and compare it
        val splitMem = splitCls.getMethod("getMemory").invoke(splitInst) as ByteBuffer
        assertEquals(mem, splitMem)
        // Dump some info
        logger.debug {
            val orig = ctx.mod.funcs.first()
            val (new, split) = splitMod.funcs.let { it.first() to it.last() }
            "Split complete, from single func with ${orig.instructions.size} insns to func " +
                "with ${new.instructions.size} insns + delegated func " +
                "with ${split.instructions.size} insns and ${split.type.params.size} params"
        }
    }
}