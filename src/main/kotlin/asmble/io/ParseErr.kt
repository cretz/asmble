package asmble.io

import asmble.AsmErr

sealed class ParseErr(message: String, cause: Throwable? = null) : RuntimeException(message, cause), AsmErr {

}