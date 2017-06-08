package run.jvm.emscripten;

import asmble.annotation.WasmExport;

public class Common {
    private final Env env;

    public Common(Env env) {
        this.env = env;
    }

    public void abort() {
        throw new UnsupportedOperationException();
    }

    @WasmExport("__assert_fail")
    public void assertFail(int conditionPtr, int filenamePtr, int line, int funcPtr) {
        throw new AssertionError("Assertion failed: " + env.mem.getCString(conditionPtr) + ", at " +
            env.mem.getCString(filenamePtr) + ":" + line + ", func " + env.mem.getCString(funcPtr));
    }

    public int atexit(int funcPtr) {
        env.addCallback(funcPtr, null);
        return 0;
    }

    public int atexit(int funcPtr, int arg) {
        env.addCallback(funcPtr, arg);
        return 0;
    }

    @WasmExport("__cxa_call_unexpected")
    public void callUnexpected(int ex) {
        throw new EmscriptenException("Unexpected: " + ex);
    }

    @WasmExport("__lock")
    public void lock(int arg) {
        throw new UnsupportedOperationException();
    }

    public int sbrk(int increment) {
        throw new UnsupportedOperationException();
    }

    @WasmExport("__unlock")
    public void unlock(int arg) {
        throw new UnsupportedOperationException();
    }
}
