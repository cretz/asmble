package asmble

import asmble.ast.Node
import asmble.ast.Script
import asmble.run.jvm.ScriptAssertionError
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

class SpecTestUnit(name: String, wast: String, expectedOutput: String?) : BaseTestUnit(name, wast, expectedOutput) {

    override val shouldFail get() = name.endsWith(".fail")

    override val defaultMaxMemPages get() = when (name) {
        "nop" -> 20
        "memory_grow" -> 830
        "imports" -> 5
        else -> 1
    }

    override fun warningInsteadOfErrReason(t: Throwable) = when (name) {
        // NaN bit patterns can be off
        "float_literals", "float_exprs", "float_misc" ->
            if (isNanMismatch(t)) "NaN JVM bit patterns can be off" else null
        // We don't hold table capacity right now
        // TODO: Figure out how we want to store/retrieve table capacity. Right now
        // a table is an array, so there is only size not capacity. Since we want to
        // stay w/ the stdlib of the JVM, the best option may be to store the capacity
        // as a separate int value and query it or pass it around via import as
        // necessary. I guess I could use a vector, but it's not worth it just for
        // capacity since you lose speed.
        "imports" -> {
            val isTableMaxErr = t is ScriptAssertionError && (t.assertion as? Script.Cmd.Assertion.Unlinkable).let {
                it != null && it.failure == "incompatible import type" &&
                    it.module.imports.singleOrNull()?.kind is Node.Import.Kind.Table
            }
            if (isTableMaxErr) "Table max capacities are not validated" else null
        }
        else -> null
    }

    private fun isNanMismatch(t: Throwable) = t is ScriptAssertionError && (
        t.assertion is Script.Cmd.Assertion.ReturnNan ||
            (t.assertion is Script.Cmd.Assertion.Return && (t.assertion as Script.Cmd.Assertion.Return).let {
                it.exprs.any { it.any(this::insnIsNanConst) } ||
                    ((it.action as? Script.Cmd.Action.Invoke)?.string?.contains("nan") ?: false)
            })
        )

    private fun insnIsNanConst(i: Node.Instr) = when (i) {
        is Node.Instr.F32Const -> i.value.isNaN()
        is Node.Instr.F64Const -> i.value.isNaN()
        else -> false
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
