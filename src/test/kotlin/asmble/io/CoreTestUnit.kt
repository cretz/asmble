package asmble.io

import asmble.ast.SExpr
import asmble.util.Either
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

class CoreTestUnit(val name: String, val ast: SExpr.Multi, val expectedOutput: String?) {

    override fun toString() = "Spec unit: $name"

    companion object {
        val unitsPath = "/spec/test/core"
        fun loadAll(): List<CoreTestUnit> {
            val jcls = CoreTestUnit::class.java
            val uri = jcls.getResource(unitsPath).toURI()
            val fs = if (uri.scheme == "jar") FileSystems.newFileSystem(uri, emptyMap<String, Any>()) else null
            fs.use { fs ->
                val path = fs?.getPath(unitsPath) ?: Paths.get(uri)
                return Files.walk(path, 1).filter { it.toString().endsWith(".wast") }.map {
                    println("Loading $it")
                    val parseResult = StrToSExpr.parse(it.toUri().toURL().readText())
                    when(parseResult) {
                        is Either.Right -> throw Exception("Error loading $it: ${parseResult.v}")
                        is Either.Left -> {
                            val name = it.fileName.toString().substringBeforeLast(".wast")
                            val expectedOutput =
                                jcls.getResource("/spec/test/core/expected-output/$name.wast.log")?.readText()
                            CoreTestUnit(name, parseResult.v, expectedOutput)
                        }
                    }
                }.collect(Collectors.toList())
            }
        }
    }
}
