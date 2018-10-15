package asmble.run.jvm

import asmble.BaseTestUnit
import asmble.TestBase
import asmble.ast.Node
import asmble.ast.Script
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
        var scriptContext = ScriptContext(
            logger = this,
            builder = builder
        ).withHarnessRegistered(PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true))

        // This will fail assertions as necessary
        scriptContext = unit.script.commands.fold(scriptContext) { scriptContext, cmd ->
            try {
                scriptContext.runCommand(cmd)
            } catch (t: Throwable) {
                val warningReason = warningInsteadOfErrReason(t) ?: throw t
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

    // TODO: move this into the script context for specific assertions so the rest can continue running
    open fun warningInsteadOfErrReason(t: Throwable): String? =  when (unit.name) {
        // NaN bit patterns can be off
        "float_literals", "float_exprs", "float_misc", "f32_bitwise" ->
            if (isNanMismatch(t)) "NaN JVM bit patterns can be off" else null
        // We don't hold table capacity right now
        // TODO: Figure out how we want to store/retrieve table capacity. Right now
        // a table is an array, so there is only size not capacity. Since we want to
        // stay w/ the stdlib of the JVM, the best option may be to store the capacity
        // as a separate int value and query it or pass it around via import as
        // necessary. I guess I could use a vector, but it's not worth it just for
        // capacity since you lose speed.
        "imports" -> {
            val isTableMaxErr = t is ScriptAssertionError && (t.assertion as? Script.Cmd.Assertion.Unlinkable).let {
                it != null && it.failure == "incompatible import type" &&
                    it.module.imports.singleOrNull()?.kind is Node.Import.Kind.Table
            }
            if (isTableMaxErr) "Table max capacities are not validated" else null
        }
        else -> null
    }

    private fun isNanMismatch(t: Throwable) = t is ScriptAssertionError && (
        t.assertion is Script.Cmd.Assertion.ReturnNan ||
            (t.assertion is Script.Cmd.Assertion.Return && (t.assertion as Script.Cmd.Assertion.Return).let {
                it.exprs.any { it.any(this::insnIsNanConst) } ||
                    ((it.action as? Script.Cmd.Action.Invoke)?.string?.contains("nan") ?: false)
            })
        )

    private fun insnIsNanConst(i: Node.Instr) = when (i) {
        is Node.Instr.F32Const -> i.value.isNaN()
        is Node.Instr.F64Const -> i.value.isNaN()
        else -> false
    }
}