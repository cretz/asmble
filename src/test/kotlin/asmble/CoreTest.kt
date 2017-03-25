package asmble

import asmble.ast.Script
import asmble.compile.jvm.AstToAsm
import asmble.compile.jvm.ClsContext
import asmble.compile.jvm.withComputedFramesAndMaxs
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.util.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter

@RunWith(Parameterized::class)
class CoreTest(val unit: CoreTestUnit) {

    @Test
    fun testName() {
        println()
        println("AST SExpr: " + unit.ast)
        println("AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()))
        println("AST: " + unit.script)
        println("AST Str: " +  SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()))

        // Just the modules for now, assertions later
        unit.script.commands.mapNotNull { (it as? Script.Cmd.Module)?.module }.forEachIndexed { index, module ->
            val ctx = ClsContext(
                packageName = "foo.bar",
                className = unit.name.capitalize(),
                mod = module,
                logger = Logger.Print(Logger.Level.TRACE),
                eagerFailLargeMemOffset = false
            )
            AstToAsm.fromModule(ctx)
            // Just print it out
            println("ASM Class:")
            ctx.cls.withComputedFramesAndMaxs().accept(TraceClassVisitor(PrintWriter(System.out)))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = CoreTestUnit.loadAll()
    }
}
