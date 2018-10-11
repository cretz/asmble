package asmble.run.jvm

import asmble.BaseTestUnit
import asmble.TestBase
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

abstract class TestRunner<out T : BaseTestUnit>(val unit: T) : TestBase() {

    @Test
    fun test() {
        unit.skipRunReason?.let { Assume.assumeTrue("Skipping ${unit.name}, reason: $it", false) }
        val ex = try { run(); null } catch (e: Throwable) { e }
        if (unit.shouldFail) {
            assertNotNull(ex, "Expected failure, but succeeded")
            debug { "Got expected failure: $ex" }
        } else if (ex != null) throw ex
    }

    abstract val builder: ModuleBuilder<*>

    open fun run(): ScriptContext {
        debug { "AST SExpr: " + unit.ast }
        debug { "AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()) }
        debug { "AST: " + unit.script }
        debug { "AST Str: " + SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()) }

        val out = ByteArrayOutputStream()
        var scriptContext = ScriptContext(logger = this, builder = builder).
            withHarnessRegistered(PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true))

        // This will fail assertions as necessary
        scriptContext = unit.script.commands.fold(scriptContext) { scriptContext, cmd ->
            try {
                scriptContext.runCommand(cmd)
            } catch (t: Throwable) {
                val warningReason = unit.warningInsteadOfErrReason(t) ?: throw t
                warn { "Unexpected error on ${unit.name}, but is a warning. Reason: $warningReason. Orig err: $t" }
                scriptContext
            }
        }

        // Check the output
        unit.expectedOutput?.let {
            // Sadly, sometimes the expected output is trimmed in Emscripten tests
            assertEquals(it.trimEnd(), out.toByteArray().toString(Charsets.UTF_8).trimEnd())
        }

        return scriptContext
    }
}