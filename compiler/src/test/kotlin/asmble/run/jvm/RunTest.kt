package asmble.run.jvm

import asmble.SpecTestUnit
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RunTest(unit: SpecTestUnit) : TestRunner<SpecTestUnit>(unit) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
