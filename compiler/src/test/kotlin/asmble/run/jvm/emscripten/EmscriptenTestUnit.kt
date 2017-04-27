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
            "core/test_addr_of_stacked",
            // TODO: this fails for me even in the browser on windows
            // "core/test_alloca",
            "core/test_alloca_stack",
            "core/test_array2",
            "core/test_array2b",
            // TODO: use of undeclared identifier 'true'
            // "core/test_assert",
            // TODO: I'm running the callbacks, but nothing happening
            // "core/test_atexit",
            "core/test_atomic",
            // TODO: lots of special calls, wait for emscripten-wasm to finish out
            // "core/test_atomic_cxx",
            "core/test_atoX",
            // TODO: must use 'struct' tag to refer to type 'Struct'
            // "core/test_bigarray",
            // TODO: must use 'struct' tag to refer to type 'bitty'
            // "core/test_bitfields"
            "core/test_bsearch"
            // TODO: unknown type name '_LIBCPP_BEGIN_NAMESPACE_STD'
            // "core/test_bswap64"
            // TODO: the rest...maybe if emscripten is cleaned up
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
                        val cmdArgs = emccCommand + cFile.toString() +
                            arrayOf("-s", "WASM=1", "-o", "$nameSansExt.html")
                        TestBase.logger.debug { "Running ${cmdArgs.joinToString(" ")}" }
                        val proc = ProcessBuilder(*cmdArgs).
                            directory(tempDir).
                            redirectErrorStream(true).
                            also { it.environment() += "BINARYEN" to wasmInstall.toString() }.
                            start()
                        proc.inputStream.bufferedReader().forEachLine { TestBase.logger.debug { "[OUT] $it" } }
                        Assert.assertTrue("Timeout", proc.waitFor(10, TimeUnit.SECONDS))
                        Assert.assertEquals(0, proc.exitValue())
                        var outFile = cFile.resolveSibling("$nameSansExt.out")
                        if (Files.notExists(outFile)) {
                            outFile = cFile.resolveSibling("$nameSansExt.txt")
                            require(Files.exists(outFile)) { "Cannot find out file for $cFile" }
                        }
                        EmscriptenTestUnit(
                            name = cFile.unitNameFromCFile(),
                            wast = File(tempDir, "$nameSansExt.wast").readText(),
                            expectedOutput = outFile.toFile().readText()
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