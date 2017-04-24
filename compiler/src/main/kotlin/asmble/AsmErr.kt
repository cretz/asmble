package asmble

interface AsmErr {
    val asmErrString: String? get() = null
    val asmErrStrings get() = asmErrString?.let { listOf(it) } ?: emptyList()
}