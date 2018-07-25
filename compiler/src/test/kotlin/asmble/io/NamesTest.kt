package asmble.io

import asmble.ast.Node
import org.junit.Test
import kotlin.test.assertEquals

class NamesTest {
    @Test
    fun testNames() {
        // First, make sure it can parse from sexpr
        val (_, mod1) = SExprToAst.toModule(StrToSExpr.parseSingleMulti("""
            (module ${'$'}mod_name
                (import "foo" "bar" (func ${'$'}import_func (param i32)))
                (type ${'$'}some_sig (func (param ${'$'}type_param i32)))
                (func ${'$'}some_func
                    (type ${'$'}some_sig)
                    (param ${'$'}func_param i32)
                    (local ${'$'}func_local0 i32)
                    (local ${'$'}func_local1 f64)
                )
            )
        """.trimIndent()))
        val expected = Node.NameSection(
            moduleName = "mod_name",
            funcNames = mapOf(
                0 to "import_func",
                1 to "some_func"
            ),
            localNames = mapOf(
                1 to mapOf(
                    0 to "func_param",
                    1 to "func_local0",
                    2 to "func_local1"
                )
            )
        )
        assertEquals(expected, mod1.names)
        // Now back to binary and then back and make sure it's still there
        val bytes = AstToBinary.fromModule(mod1)
        val mod2 = BinaryToAst.toModule(bytes)
        assertEquals(expected, mod2.names)
        // Now back to sexpr and then back to make sure the sexpr writer works
        val sexpr = AstToSExpr.fromModule(mod2)
        val (_, mod3) = SExprToAst.toModule(sexpr)
        assertEquals(expected, mod3.names)
    }
}