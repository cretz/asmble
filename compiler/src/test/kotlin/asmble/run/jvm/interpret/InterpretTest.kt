package asmble.run.jvm.interpret

import asmble.SpecTestUnit
import asmble.ast.Script
import asmble.run.jvm.ScriptAssertionError
import asmble.run.jvm.TestRunner
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class InterpretTest(unit: SpecTestUnit) : TestRunner<SpecTestUnit>(unit) {

    override val builder get() = RunModule.Builder(
        logger = logger,
        defaultMaxMemPages = unit.defaultMaxMemPages
    )

    // Some things require compilation of code, something the interpreter doesn't do until execution time
    override fun warningInsteadOfErrReason(t: Throwable) = super.warningInsteadOfErrReason(t) ?: run {
        // Interpreter doesn't eagerly validate
        if ((t as? ScriptAssertionError)?.assertion is Script.Cmd.Assertion.Invalid)
            return "Interpreter doesn't eagerly validate"
        // Other units specifically...
        when (unit.name) {
            "if" -> {
                // Couple of tests expect mismatching label to be caught at compilation without execution
                val compileErr = ((t as? ScriptAssertionError)?.
                    assertion as? Script.Cmd.Assertion.Malformed)?.failure == "mismatching label"
                if (compileErr) "Interpreter doesn't check unexecuted code" else null
            }
            "imports", "linking" -> (t as? ScriptAssertionError)?.assertion?.let { assertion ->
                when (assertion) {
                    // Couple of tests expect imports to be checked without attempted resolution
                    is Script.Cmd.Assertion.Unlinkable -> when (assertion.failure) {
                        "unknown import", "incompatible import type" ->
                            "Interpreter doesn't check unexecuted code"
                        else -> null
                    }
                    // There is a test that expects none of the table elems to be set if there is any module link
                    // failure previously. The problem is the interpreter runs until it can't and is non-atomic
                    is Script.Cmd.Assertion.Trap ->
                        if (assertion.failure == "uninitialized") "Interpreter doesn't initialize atomically"
                        else null
                    // Same here, expects previous data to have never been placed
                    is Script.Cmd.Assertion.Return -> (assertion.action as? Script.Cmd.Action.Invoke)?.let { invoke ->
                        if (invoke.name == "Mm" && invoke.string == "load") "Interpreter doesn't initialize atomically"
                        else null
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
