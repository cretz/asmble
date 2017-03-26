package asmble

import asmble.ast.SExpr
import asmble.ast.Script
import asmble.io.SExprToAst
import asmble.io.StrToSExpr
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

class CoreTestUnit(val name: String, val wast: String, val expectedOutput: String?) {

    override fun toString() = "Spec unit: $name"

    val parseResult: StrToSExpr.ParseResult.Success by lazy {
        StrToSExpr.parse(wast).let {
            when (it) {
                is StrToSExpr.ParseResult.Error -> throw Exception("$name[${it.pos}] Parse fail: ${it.msg}")
                is StrToSExpr.ParseResult.Success -> it
            }
        }
    }

    val ast: List<SExpr> get() = parseResult.vals

    val script: Script by lazy { SExprToAst.toScript(SExpr.Multi(ast)) }

    companion object {
        val unitsPath = "/spec/test/core"
        fun loadAll(): List<CoreTestUnit> {
            return loadFromResourcePath("/local-spec") + loadFromResourcePath(unitsPath)
        }

        fun loadFromResourcePath(basePath: String): List<CoreTestUnit> {
            require(basePath.last() != '/')
            val jcls = CoreTestUnit::class.java
            val uri = jcls.getResource(basePath).toURI()
            val fs = if (uri.scheme == "jar") FileSystems.newFileSystem(uri, emptyMap<String, Any>()) else null
            fs.use { fs ->
                val path = fs?.getPath(basePath) ?: Paths.get(uri)
                return Files.walk(path, 1).filter { it.toString().endsWith("block.wast") }.map {
                    val name = it.fileName.toString().substringBeforeLast(".wast")
                    CoreTestUnit(
                        name = name,
                        wast = it.toUri().toURL().readText(),
                        expectedOutput = jcls.getResource("$basePath/expected-output/$name.wast.log")?.readText()
                    )
                }.collect(Collectors.toList())
            }
        }
    }
}
