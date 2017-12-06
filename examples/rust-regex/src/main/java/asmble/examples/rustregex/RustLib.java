package asmble.examples.rustregex;

import asmble.generated.RustRegex;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class RustLib implements RegexLib<RustLib.Ptr> {

    // 300 pages is good for now
    private static final int PAGE_SIZE = 65536;
    private static final int MAX_MEMORY = 300 * PAGE_SIZE;

    private final RustRegex rustRegex;

    public RustLib() {
        rustRegex = new RustRegex(MAX_MEMORY);
    }

    @Override
    public RustPattern compile(String str) {
        return new RustPattern(str);
    }
    
    @Override
    public Ptr prepareTarget(String target) {
        return ptrFromString(target);
    }

    private Ptr ptrFromString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        Ptr ptr = new Ptr(bytes.length);
        ptr.put(bytes);
        return ptr;
    }

    public class RustPattern implements RegexPattern<Ptr> {

        private final int pointer;

        private RustPattern(String pattern) {
            Ptr ptr = ptrFromString(pattern);
            pointer = rustRegex.compile_pattern(ptr.offset, ptr.size);
        }

        @Override
        protected void finalize() throws Throwable {
            rustRegex.dispose_pattern(pointer);
        }

        @Override
        public int matchCount(Ptr target) {
            return rustRegex.match_count(pointer, target.offset, target.size);
        }
    }

    public class Ptr {

        final int offset;
        final int size;

        Ptr(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }

        Ptr(int size) {
            this(rustRegex.alloc(size), size);
        }

        void put(byte[] bytes) {
            // Yeah, yeah, not thread safe
            ByteBuffer memory = rustRegex.getMemory();
            memory.position(offset);
            memory.put(bytes);
        }

        @Override
        protected void finalize() throws Throwable {
            rustRegex.dealloc(offset, size);
        }
    }
}
