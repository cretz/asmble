package asmble.run.jvm.emscripten

import asmble.run.jvm.TestRunner
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EmscriptenTest(unit: EmscriptenTestUnit) : TestRunner<EmscriptenTestUnit>(unit) {
    companion object {
        var failureReason: Throwable? = null

        @JvmStatic @BeforeClass
        fun setup() { failureReason?.also { throw it } }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = try { EmscriptenTestUnit.allUnits } catch (t: Throwable) { failureReason = t; listOf(null) }
    }
}