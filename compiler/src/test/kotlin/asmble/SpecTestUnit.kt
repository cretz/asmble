package asmble

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

class SpecTestUnit(name: String, wast: String, expectedOutput: String?) : BaseTestUnit(name, wast, expectedOutput) {

    override val shouldFail get() = name.endsWith(".fail")

    override val defaultMaxMemPages get() = when (name) {
        "call", "call_indirect" -> 310
        "globals", "imports", "select" -> 7
        "memory_grow" -> 830
        "nop" -> 20
        else -> 2
    }

    companion object {
        val unitsPath = "/spec/test/core"

        val allUnits by lazy { loadFromResourcePath("/local-spec") + loadFromResourcePath(unitsPath) }

        fun loadFromResourcePath(basePath: String): List<SpecTestUnit> {
            require(basePath.last() != '/')
            val jcls = SpecTestUnit::class.java
            val uri = jcls.getResource(basePath).toURI()
            val fs = if (uri.scheme == "jar") FileSystems.newFileSystem(uri, emptyMap<String, Any>()) else null
            return fs.use { fs ->
                val path = fs?.getPath(basePath) ?: Paths.get(uri)
                val testWastFiles = Files.walk(path, 1).filter { it.toString().endsWith(".wast") }
                testWastFiles.map {
                    val name = it.fileName.toString().substringBeforeLast(".wast")
                    SpecTestUnit(
                        name = name,
                        wast = it.toUri().toURL().readText(),
                        expectedOutput = jcls.getResource("$basePath/expected-output/$name.wast.log")?.readText()
                    )
                }.collect(Collectors.toList())
            }
        }
    }
}
