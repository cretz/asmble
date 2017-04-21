package asmble.run.jvm

import asmble.SpecTestUnit
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import asmble.run.jvm.emscripten.Env
import asmble.util.Logger
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(Parameterized::class)
class RunTest(val unit: SpecTestUnit) : Logger by Logger.Print(Logger.Level.INFO) {

    @Test
    fun testRun() {
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
            packageName = "asmble.temp.${unit.name}",
            logger = this,
            adjustContext = { it.copy(eagerFailLargeMemOffset = false) },
            defaultMaxMemPages = unit.defaultMaxMemPages
        ).withHarnessRegistered(PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true))

        // If there's a staticBump, we are an emscripten mod and we need to include the env
        unit.emscriptenStaticBump?.also { staticBump ->
            scriptContext = scriptContext.withModuleRegistered("env", Env.module(this, staticBump, out))
        }

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

        // If there is a main, we run it w/ no args because emscripten doesn't set it as the start func
        scriptContext.modules.lastOrNull()?.also { lastMod ->
            lastMod.cls.methods.find {
                it.name == "main" &&
                it.returnType == Int::class.java &&
                it.parameterTypes.asList() == listOf(Int::class.java, Int::class.java)
            }?.invoke(lastMod.instance(scriptContext), 0, 0)
        }

        unit.expectedOutput?.let { assertEquals(it, out.toByteArray().toString(Charsets.UTF_8)) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
