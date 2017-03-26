package asmble

import asmble.ast.SExpr
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.run.jvm.ScriptContext
import asmble.util.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class CoreTest(val unit: CoreTestUnit) : Logger by Logger.Print(Logger.Level.TRACE) {

    @Test
    fun testName() {

        debug { "AST SExpr: " + unit.ast }
        debug { "AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()) }
        debug { "AST: " + unit.script }
        debug { "AST Str: " +  SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()) }

        val out = StringWriter()
        val scriptContext = ScriptContext(
            packageName = "asmble.temp.${unit.name}",
            logger = this,
            adjustContext = { it.copy(eagerFailLargeMemOffset = false) }
        ).withHarnessRegistered(PrintWriter(out))

        // This will fail assertions as necessary
        unit.script.commands.fold(scriptContext, ScriptContext::runCommand)

        unit.expectedOutput?.let { assertEquals(it, out.toString()) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = CoreTestUnit.loadAll()
    }
}
