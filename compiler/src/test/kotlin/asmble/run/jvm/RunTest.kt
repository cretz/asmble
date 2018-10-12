package asmble.run.jvm

import asmble.SpecTestUnit
import asmble.annotation.WasmModule
import asmble.ast.Node
import asmble.ast.Script
import asmble.io.AstToBinary
import asmble.io.ByteWriter
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class RunTest(unit: SpecTestUnit) : TestRunner<SpecTestUnit>(unit) {

    override val builder get() = ModuleBuilder.Compiled(
        packageName = unit.packageName,
        logger = this,
        adjustContext = { it.copy(eagerFailLargeMemOffset = false) },
        // Include the binary data so we can check it later
        includeBinaryInCompiledClass = true,
        defaultMaxMemPages = unit.defaultMaxMemPages
    )

    override fun run() = super.run().also { scriptContext ->
        // Check annotations
        scriptContext.modules.forEach { mod ->
            mod as Module.Compiled
            val expectedBinaryString = ByteArrayOutputStream().also {
                ByteWriter.OutputStream(it).also { AstToBinary.fromModule(it, mod.mod) }
            }.toByteArray().toString(Charsets.ISO_8859_1)
            val actualBinaryString =
                mod.cls.getDeclaredAnnotation(WasmModule::class.java)?.binary ?: error("No annotation")
            assertEquals(expectedBinaryString, actualBinaryString)
        }
    }

    override fun warningInsteadOfErrReason(t: Throwable) = when (unit.name) {
        "binary" -> {
            val expectedFailure = ((t as? ScriptAssertionError)?.assertion as? Script.Cmd.Assertion.Malformed)?.failure
            // TODO: Pending answer to https://github.com/WebAssembly/spec/pull/882#issuecomment-426349365
            if (expectedFailure == "integer too large") "Binary test changed" else null
        }
        // NaN bit patterns can be off
        "float_literals", "float_exprs", "float_misc" ->
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

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
