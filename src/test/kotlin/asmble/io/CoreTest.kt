package asmble.io

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CoreTest(val unit: CoreTestUnit) {

    @Test
    fun testName() {
        println()
        println("AST SExpr: " + unit.ast)
        println("AST Str: " + SExprToStr.fromSExpr(*unit.ast.toTypedArray()))
        println("AST: " + unit.script)
        println("AST Str: " +  SExprToStr.fromSExpr(*AstToSExpr.fromScript(unit.script).toTypedArray()))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = CoreTestUnit.loadAll()
    }
}


