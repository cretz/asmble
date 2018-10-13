package asmble.run.jvm

import asmble.SpecTestUnit
import asmble.annotation.WasmModule
import asmble.io.AstToBinary
import asmble.io.ByteWriter
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class RunTest(unit: SpecTestUnit) : TestRunner<SpecTestUnit>(unit) {

    override val builder get() = ModuleBuilder.Compiled(
        packageName = unit.packageName,
        logger = this,
        adjustContext = { it.copy(eagerFailLargeMemOffset = false) },
        // Include the binary data so we can check it later
        includeBinaryInCompiledClass = true,
        defaultMaxMemPages = unit.defaultMaxMemPages
    )

    override fun run() = super.run().also { scriptContext ->
        // Check annotations
        scriptContext.modules.forEach { mod ->
            mod as Module.Compiled
            val expectedBinaryString = ByteArrayOutputStream().also {
                ByteWriter.OutputStream(it).also { AstToBinary.fromModule(it, mod.mod) }
            }.toByteArray().toString(Charsets.ISO_8859_1)
            val actualBinaryString =
                mod.cls.getDeclaredAnnotation(WasmModule::class.java)?.binary ?: error("No annotation")
            assertEquals(expectedBinaryString, actualBinaryString)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun data() = SpecTestUnit.allUnits
    }
}
