package run.jvm.emscripten;

import asmble.annotation.WasmName;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class Env {
    public static final int TOTAL_STACK = 5242880;
    public static final int TOTAL_MEMORY = 16777216;

    public static final List<Function<Env, Object>> subModules = Arrays.asList(
        Syscall::new
    );

    private static int alignTo16(int num) {
        return ((int) Math.ceil(num / 16.0)) * 16;
    }

    private final ByteBuffer memory;
    private final int staticBump;
    final OutputStream out;

    public Env(int staticBump, OutputStream out) {
        this(ByteBuffer.allocateDirect(TOTAL_MEMORY), staticBump, out);
    }

    public Env(ByteBuffer memory, int staticBump, OutputStream out) {
        this.memory = memory.order(ByteOrder.LITTLE_ENDIAN);
        this.staticBump = staticBump;
        this.out = out;
        // Emscripten sets where "stack top" can start in mem at position 1024.
        // See https://github.com/WebAssembly/binaryen/issues/979
        int stackBase = alignTo16(staticBump + 1024 + 16);
        int stackTop = stackBase + TOTAL_STACK;
        memory.putInt(1024, stackTop);
    }

    public ByteBuffer getMemory() {
        return memory;
    }

    public byte[] getMemoryBulk(int index, int len) {
        byte[] ret = new byte[len];
        ByteBuffer dup = memory.duplicate();
        dup.position(index);
        dup.get(ret);
        return ret;
    }

    public void abort() {
        throw new UnsupportedOperationException();
    }

    @WasmName("__lock")
    public void lock(int arg) {
        throw new UnsupportedOperationException();
    }

    public int sbrk(int increment) {
        throw new UnsupportedOperationException();
    }

    @WasmName("__unlock")
    public void unlock(int arg) {
        throw new UnsupportedOperationException();
    }
}
