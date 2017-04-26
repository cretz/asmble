package asmble.run.jvm.emscripten

import asmble.BaseTestUnit
import asmble.TestBase
import org.junit.Assert
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

class EmscriptenTestUnit(
    name: String,
    wast: String,
    expectedOutput: String
) : BaseTestUnit(name, wast, expectedOutput) {
    companion object {
        val knownGoodNames = listOf(
            "core/test_addr_of_stacked"
        )

        val allUnits by lazy { loadUnits() }

        fun loadUnits(): List<EmscriptenTestUnit> {
            val wasmInstall = Paths.get(
                System.getenv("WASM_INSTALL") ?: throw AssumptionViolatedException("WASM_INSTALL not set")
            )
            val testBase = wasmInstall.resolve("emscripten/tests")
            fun Path.unitNameFromCFile() =
                testBase.relativize(this).toString().substringBeforeLast(".c").replace('\\', '/')
            // Obtain C files we know work
            val goodCFiles = Files.walk(testBase).
                filter { knownGoodNames.contains(it.unitNameFromCFile()) }.
                collect(Collectors.toList())
            // Go over each one and create a test unit
            val tempDir = createTempDir("emscriptenout")
            val isWindows = System.getProperty("os.name").contains("windows", true)
            val emccCommand =
                if (isWindows) arrayOf("cmd", "/c", wasmInstall.resolve("emscripten/emcc.bat").toString())
                else arrayOf(wasmInstall.resolve("emscripten/emcc").toString())
            try {
                return goodCFiles.map { cFile ->
                    try {
                        // Run emcc on the cFile
                        val nameSansExt = cFile.fileName.toString().substringBeforeLast(".c")
                        val cmdArgs = emccCommand + cFile.toString() + "-s" + "WASM=1" + "-o" + "$nameSansExt.html"
                        TestBase.logger.debug { "Running ${cmdArgs.joinToString(" ")}" }
                        val proc = ProcessBuilder(*cmdArgs).
                            directory(tempDir).
                            redirectErrorStream(true).
                            also { it.environment() += "BINARYEN" to wasmInstall.toString() }.
                            start()
                        proc.inputStream.bufferedReader().forEachLine { TestBase.logger.debug { "[OUT] $it" } }
                        Assert.assertTrue("Timeout", proc.waitFor(10, TimeUnit.SECONDS))
                        Assert.assertEquals(0, proc.exitValue())
                        EmscriptenTestUnit(
                            name = cFile.unitNameFromCFile(),
                            wast = File(tempDir, "$nameSansExt.wast").readText(),
                            expectedOutput = cFile.resolveSibling("$nameSansExt.out").toFile().readText()
                        )
                    } catch (e: Exception) { throw Exception("Unable to compile $cFile", e) }
                }
            } finally {
                try { tempDir.deleteRecursively() }
                catch (e: Exception) { TestBase.logger.warn { "Unable to delete temp dir: $e" } }
            }
        }
    }
}