package asmble.io

sealed class ImportOrExport {
    abstract val itemCount: Int

    data class Import(val name: String, val module: String, val exportFields: List<String>) : ImportOrExport() {
        override val itemCount get() = 1 + exportFields.size
    }

    data class Export(val fields: List<String>) : ImportOrExport() {
        override val itemCount get() = fields.size
    }
}