package asmble.run.jvm.emscripten

class Stdio(val env: Env) {

    fun printf(format: Int, argStart: Int) = format(format, argStart).let { formatted ->
        env.out.write(formatted.toByteArray(Charsets.ISO_8859_1))
        formatted.length
    }

    private fun format(format: Int, argStart: Int): String {
        // TODO: the rest of this. We should actually take musl, compile it to the JVM,
        // and then go from there. Not musl.wast which has some imports of its own.
        val str = env.readCString(format)
        // Only support %s for now...
        val strReplacementIndices = str.foldIndexed(emptyList<Int>()) { index, indices, char ->
            if (char != '%') indices
            else if (str.getOrNull(index + 1) != 's') error("Only '%s' supported for now")
            else indices + index
        }
        val strs = strReplacementIndices.indices.map { index ->
            env.readCString(env.memory.getInt(argStart + (index * 4)))
        }
        // Replace reversed
        return strReplacementIndices.zip(strs).asReversed().fold(str) { str, (index, toPlace) ->
            str.substring(0, index) + toPlace + str.substring(index + 2)
        }
    }
}