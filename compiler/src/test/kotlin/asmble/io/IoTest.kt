package asmble.io

import asmble.SpecTestUnit
import asmble.TestBase
import asmble.ast.Node
import asmble.ast.Script
import asmble.util.Logger
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class IoTest(val unit: SpecTestUnit) : TestBase() {

    @Test
    fun testIo() {
        // Go from the AST to binary then back to AST then back to binary and confirm values are as expected
        val ast1 = unit.script.commands.mapNotNull { (it as? Script.Cmd.Module)?.module?.also {
            trace { "AST from script:\n" + SExprToStr.fromSExpr(AstToSExpr.fromModule(it)) }
        } }
        val out = ByteArrayOutputStream()
        fun toBinary(mod: Node.Module) =
            AstToBinary.fromModule(ByteWriter.OutputStream(out.also { it.reset() }), mod).run { out.toByteArray() }
        val binaries1 = ast1.map(::toBinary)
        val ast2 = binaries1.map {
            trace { "Bytes for AST (${it.size}): ${it.asList()}" }
            BinaryToAst(logger = this).toModule(ByteReader.InputStream(ByteArrayInputStream(it))).also {
                trace { "AST from bytes:\n" + SExprToStr.fromSExpr(AstToSExpr.fromModule(it)) }
                it.customSections.forEach {
                    trace { "Custom section ${it.name} after ${it.afterSectionId} with payload ${it.payload.asList()}" }
                }
            }
        }
        // Compare AST's, but we reset ast2's types because those can change
        assertEquals(ast1.size, ast2.size)
        ast1.zip(ast2) { expected, actual -> assertEquals(expected, actual.copy(types = expected.types)) }
        // Turn back to binary and compare them
        val binaries2 = ast2.map(::toBinary)
        assertEquals(binaries1.size, binaries2.size)
        binaries1.zip(binaries2, ::assertArrayEquals)
    }

    companion object {
        // Only tests that shouldn't fail
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits.filterNot { it.shouldFail }
    }
}
