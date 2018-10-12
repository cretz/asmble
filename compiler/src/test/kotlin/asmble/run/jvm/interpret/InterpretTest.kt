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

    override fun warningInsteadOfErrReason(t: Throwable) = when (unit.name) {
        "binary" -> {
            val expectedFailure = ((t as? ScriptAssertionError)?.assertion as? Script.Cmd.Assertion.Malformed)?.failure
            // TODO: Pending answer to https://github.com/WebAssembly/spec/pull/882#issuecomment-426349365
            if (expectedFailure == "integer too large") "Binary test changed" else null
        }
        else -> null
    }

    override fun excludeAssertion(a: Script.Cmd.Assertion) = when (a) {
        // We don't validate entire modules when interpreting them
        is Script.Cmd.Assertion.Invalid -> true
        else -> false
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
