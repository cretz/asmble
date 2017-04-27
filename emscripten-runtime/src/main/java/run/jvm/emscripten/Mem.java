package run.jvm.emscripten;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class Mem {
    public static final int TOTAL_STACK = 5242880;
    public static final int TOTAL_MEMORY = 16777216;

    private static int alignTo16(int num) {
        return ((int) Math.ceil(num / 16.0)) * 16;
    }

    final ByteBuffer buf;
    private int counter = 0;

    public Mem(int staticBump) {
        this(ByteBuffer.allocateDirect(TOTAL_MEMORY), staticBump);
    }

    public Mem(ByteBuffer buf, int staticBump) {
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        // Emscripten sets where "stack top" can start in mem at position 1024.
        // See https://github.com/WebAssembly/binaryen/issues/979
        int staticTop = 1024 + staticBump + 16;
        int stackBase = alignTo16(staticTop);
        int stackTop = stackBase + TOTAL_STACK;
        buf.putInt(1024, stackTop);
    }


    public byte[] getBulk(int index, int len) {
        byte[] ret = new byte[len];
        if (len > 0) {
            ByteBuffer dup = buf.duplicate();
            dup.position(index);
            dup.get(ret);
        }
        return ret;
    }

    public byte[] getCStringBytes(int index) {
        if (index == 0) return new byte[0];
        // Not really sure the highest performing approach. What we're going to do though is just
        // find the first index of 0, then get the array.
        int len = 0;
        while (buf.get(index + len) != 0) len++;
        return getBulk(index, len);
    }

    public String getCString(int index) {
        byte[] bytes = getCStringBytes(index);
        if (bytes.length == 0) return "";
        return new String(getCStringBytes(index), StandardCharsets.ISO_8859_1);
    }

    public synchronized int putCString(String str) {
        // For now we'll just trust it doesn't already contain a 0
        int ret = allocate(str.getBytes(StandardCharsets.ISO_8859_1));
        allocate((byte) 0);
        return ret;
    }

    public int putIntArray(int[] arr) {
        int ret = counter;
        for (int i = 0; i < arr.length; i++) {
            buf.putInt(counter, arr[i]);
            counter += 4;
        }
        return ret;
    }

    public int allocate(byte b) {
        int ret = counter;
        buf.put(counter, b);
        counter++;
        return ret;
    }

    public int allocate(byte[] bytes) {
        int ret = counter;
        if (bytes.length > 0) {
            ByteBuffer dup = buf.duplicate();
            dup.position(counter);
            dup.put(bytes);
            counter += bytes.length;
        }
        return ret;
    }
}
