package asmble.run.jvm

import asmble.ast.Script

class ScriptAssertionError(
    val assertion: Script.Cmd.Assertion,
    msg: String,
    returned: Any? = null,
    expected: Any? = null,
    cause: Throwable? = null
) : AssertionError(msg, cause)
