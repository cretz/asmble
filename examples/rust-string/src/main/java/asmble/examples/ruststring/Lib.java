package asmble.examples.ruststring;

import asmble.generated.RustString;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// Not thread safe!
class Lib {
    private final RustString rustString;

    Lib(RustString rustString) {
        this.rustString = rustString;
    }

    int stringLength(String str) {
        Ptr strPtr = ptrFromString(str);
        return rustString.string_len(strPtr.offset, strPtr.size);
    }

    String prependFromRust(String str) {
        Ptr strPtr = ptrFromString(str);
        int nullTermOffset = rustString.prepend_from_rust(strPtr.offset, strPtr.size);
        return nullTermedStringFromOffset(nullTermOffset);
    }

    private Ptr ptrFromString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        Ptr ptr = new Ptr(bytes.length);
        ptr.put(bytes);
        return ptr;
    }

    private String nullTermedStringFromOffset(int offset) {
        ByteBuffer memory = rustString.getMemory();
        memory.position(offset);
        // We're going to turn the mem into an input stream. This is the
        //  reasonable way to stream a UTF8 read using standard Java libs
        //  that I could find.
        InputStreamReader r = new InputStreamReader(new InputStream() {
            @Override
            public int read() throws IOException {
                if (!memory.hasRemaining()) {
                    return -1;
                }
                return memory.get() & 0xFF;
            }
        }, StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        try {
            while (true) {
                int c = r.read();
                if (c <= 0) {
                    break;
                }
                builder.append((char) c);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        rustString.dealloc(offset, memory.position() - offset);
        return builder.toString();
    }

    class Ptr {
        final int offset;
        final int size;

        Ptr(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }

        Ptr(int size) {
            this(rustString.alloc(size), size);
        }

        void put(byte[] bytes) {
            // Yeah, yeah, not thread safe
            ByteBuffer memory = rustString.getMemory();
            memory.position(offset);
            memory.put(bytes);
        }

        @Override
        protected void finalize() throws Throwable {
            rustString.dealloc(offset, size);
        }
    }
}