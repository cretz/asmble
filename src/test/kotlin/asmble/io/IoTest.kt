package asmble.io

import asmble.SpecTestUnit
import asmble.ast.Node
import asmble.ast.Script
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.ByteBuffer
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class IoTest(val unit: SpecTestUnit) {

    @Test
    fun testIo() {
        // Ignore things that are supposed to fail
        if (unit.name.endsWith(".fail")) return
        // Go from the AST to binary then back to AST then back to binary and confirm values are as expected
        val ast1 = unit.script.commands.mapNotNull { (it as? Script.Cmd.Module)?.module }
        val buf = ByteWriter.Buffer(ByteBuffer.allocate(8096))
        fun toBinary(mod: Node.Module) =
            AstToBinary.fromModule(buf.also { it.buf.clear() }, mod).let { buf.buf.array().copyOf() }
        val binaries1 = ast1.map(::toBinary)
        val ast2 = binaries1.map { BinaryToAst.toModule(ByteReader.Buffer(ByteBuffer.wrap(it))) }
        // Compare AST's, but we reset ast2's types because those can change
        assertEquals(ast1.size, ast2.size)
        ast1.zip(ast2) { expected, actual -> assertEquals(expected, actual.copy(types = expected.types)) }
        // Turn back to binary and compare them
        val binaries2 = ast2.map(::toBinary)
        assertEquals(binaries1.size, binaries2.size)
        binaries1.zip(binaries2) { expected, actual -> assertEquals(expected, actual) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.loadAll()
    }
}
