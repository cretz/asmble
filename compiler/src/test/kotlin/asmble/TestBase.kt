package asmble

import asmble.util.Logger

abstract class TestBase : Logger by TestBase.logger {
    companion object {
        val logger = Logger.Print(Logger.Level.INFO)
    }
}