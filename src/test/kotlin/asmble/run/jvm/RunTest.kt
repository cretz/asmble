package asmble.run.jvm

import asmble.SpecTestUnit
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.util.Logger
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class RunTest(val unit: SpecTestUnit) : Logger by Logger.Print(Logger.Level.INFO) {

    @Test
    fun testRun() {
        val ex = try { run(); null } catch (e: Throwable) { e }
        if (unit.name.endsWith(".fail")) {
            assertNotNull(ex, "Expected failure, but succeeded")
            debug { "Got expected failure: $ex" }
        } else if (ex != null) {
            if (unit.isWarningInsteadOfError(ex)) warn { "Unexpected error on ${unit.name}, but is a warning: $ex" }
            else throw ex
        }
    }

    private fun run() {
        debug { "AST SExpr: " + unit.ast }
        debug { "AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()) }
        debug { "AST: " + unit.script }
        debug { "AST Str: " + SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()) }

        val out = StringWriter()
        val scriptContext = ScriptContext(
            packageName = "asmble.temp.${unit.name}",
            logger = this,
            adjustContext = { it.copy(eagerFailLargeMemOffset = false) },
            defaultMaxMemPages = unit.defaultMaxMemPages
        ).withHarnessRegistered(PrintWriter(out))

        // This will fail assertions as necessary
        unit.script.commands.fold(scriptContext, ScriptContext::runCommand)

        unit.expectedOutput?.let { assertEquals(it, out.toString()) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.loadAll()
    }
}
