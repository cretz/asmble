package asmble.ast

import asmble.SpecTestUnit
import asmble.TestBase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class StackTest(val unit: SpecTestUnit) : TestBase() {

    @Test
    fun testStack() {
        // If it's not a module expecting an error, we'll try to walk the stack on each function
        unit.script.commands.mapNotNull { it as? Script.Cmd.Module }.forEach { mod ->
            mod.module.funcs.filter { it.instructions.isNotEmpty() }.forEach { func ->
                debug { "Func: ${func.type}" }
                var indexCount = 0
                Stack.walkStrict(mod.module, func) { stack, insn ->
                    debug { "  After $insn (next: ${func.instructions.getOrNull(++indexCount)}, " +
                        "unreach depth: ${stack.unreachableUntilNextEndCount})" }
                    debug { "    " + stack.current }
                }
            }
        }
    }

    companion object {
        // Only tests that shouldn't fail
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits.filterNot { it.shouldFail }//.filter { it.name == "loop" }
    }
}