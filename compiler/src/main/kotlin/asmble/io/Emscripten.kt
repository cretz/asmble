package asmble.io

open class Emscripten {

    fun metadataFromWast(wast: String) =
        wast.lastIndexOf(";; METADATA:").takeIf { it != -1 }?.let { metaIndex ->
            wast.indexOfAny(listOf("\n", "\"staticBump\": "), metaIndex).
                takeIf { it != -1 && wast[it] != '\n' }?.
                let { bumpIndex ->
                    wast.indexOfAny(charArrayOf('\n', ','), bumpIndex).takeIf { it != -1 }?.let { commaIndex ->
                        wast.substring(bumpIndex + 14, commaIndex).trim().toIntOrNull()?.let { staticBump ->
                            Metadata(staticBump)
                        }
                    }
                }
        }

    data class Metadata(val staticBump: Int)

    companion object : Emscripten()
}