package asmble.io

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CoreTest(val unit: CoreTestUnit) {

    @Test
    fun testName() {
        println("\nYay: ${unit.name}: ${unit.ast}")
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = CoreTestUnit.loadAll()
    }
}


