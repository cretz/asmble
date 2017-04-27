package asmble.run.jvm

import asmble.BaseTestUnit
import asmble.TestBase
import asmble.io.AstToSExpr
import asmble.io.SExprToStr
import org.junit.Assume
import org.junit.Test
import run.jvm.emscripten.Env
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
            defaultMaxMemPages = unit.defaultMaxMemPages
        ).withHarnessRegistered(PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true))

        // If there's a staticBump, we are an emscripten mod and we need to include the env
        var emEnv: Env? = null
        unit.emscriptenMetadata?.also {
            emEnv = Env(it.staticBump, out, "unknown-program")
            val mods = Env.subModules.fold(listOf(Module.Native(emEnv!!))) { mods, subMod ->
                mods + Module.Native(subMod.apply(emEnv))
            }
            scriptContext = scriptContext.withModuleRegistered("env", Module.Composite(mods))
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
            lastMod.cls.methods.find { it.name == "main" && it.returnType == Int::class.java }?.let { mainMethod ->
                if (mainMethod.parameterTypes.isEmpty())
                    mainMethod.invoke(lastMod.instance(scriptContext))
                else if (mainMethod.parameterTypes.asList() == listOf(Int::class.java, Int::class.java))
                    mainMethod.invoke(lastMod.instance(scriptContext), emEnv?.argc ?: 0, emEnv?.argv ?: 0)
                else
                    error("Unrecognized main method params for $mainMethod")
            }
        }

        // If there was an emscripten env, we have to run the exit callbacks
        emEnv?.also { it.runAtExitCallbacks(scriptContext.modules.last().instance(scriptContext)) }

        // Check the output
        unit.expectedOutput?.let {
            // Sadly, sometimes the expected output is trimmed in Emscripten tests
            assertEquals(it.trimEnd(), out.toByteArray().toString(Charsets.UTF_8).trimEnd())
        }
    }
}