package asmble.temp;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

class Temp {

    public void temp() throws Throwable {
        MethodHandle mh = MethodHandles.lookup().findVirtual(Temp.class, "foo",
                MethodType.methodType(String.class));
        int ret = (int) mh.invokeExact();
        throw new UnsupportedOperationException("Unreachable: " + ret);
    }

    public static int foo() {
        return 45;
    }

    static class Module1 {

        private final ByteBuffer memory;
        private final MethodHandle spectestPrint;
//        private final MethodHandle localFunc0;

        public Module1(int amount, MethodHandle spectestPrint) {
            this(ByteBuffer.allocateDirect(amount), spectestPrint);
        }

        public Module1(ByteBuffer memory, MethodHandle spectestPrint) {
            // TODO: could check memory capacity here
            // We trust this is zeroed
            this.memory = memory;
            this.memory.limit(65536 /* 1 page */);
            this.memory.put(new byte[] { 1, 2, 3 /*...*/ }, 0, 3 /* full length */);
            this.spectestPrint = spectestPrint;

        }

        public void good(int param0) throws Throwable {
            $func1(param0);
        }

        private void $func1(int param0) throws Throwable {
            // Compiler option to determine number of accesses before it's made a local var...default 1
            ByteBuffer memory = this.memory;
            MethodHandle spectestPrint = this.spectestPrint;
            // (call $print (i32.load8_u offset=0 (get_local $i)))  ;; 97 'a'
            // iload_1
            int iload_1 = param0;
            int param_var = memory.get(iload_1);
            // TODO: compiler option to not put Throwable on functions
            spectestPrint.invokeExact(param_var);
        }
    }
}