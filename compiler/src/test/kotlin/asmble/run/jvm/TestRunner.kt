package asmble.run.jvm

import asmble.BaseTestUnit
import asmble.TestBase
import asmble.annotation.WasmModule
import asmble.io.AstToBinary
import asmble.io.AstToSExpr
import asmble.io.ByteWriter
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

    private fun run() {
        debug { "AST SExpr: " + unit.ast }
        debug { "AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()) }
        debug { "AST: " + unit.script }
        debug { "AST Str: " + SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()) }

        val out = ByteArrayOutputStream()
        var scriptContext = ScriptContext(
            packageName = unit.packageName,
            logger = this,
            adjustContext = { it.copy(eagerFailLargeMemOffset = false) },
            defaultMaxMemPages = unit.defaultMaxMemPages,
            // Include the binary data so we can check it later
            includeBinaryInCompiledClass = true
        ).withHarnessRegistered(PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true))

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

        // Also check the annotations
        scriptContext.modules.forEach { mod ->
            val expectedBinaryString = ByteArrayOutputStream().also {
                ByteWriter.OutputStream(it).also { AstToBinary.fromModule(it, mod.mod) }
            }.toByteArray().toString(Charsets.ISO_8859_1)
            val actualBinaryString =
                mod.cls.getDeclaredAnnotation(WasmModule::class.java)?.binary ?: error("No annotation")
            assertEquals(expectedBinaryString, actualBinaryString)
        }
    }
}