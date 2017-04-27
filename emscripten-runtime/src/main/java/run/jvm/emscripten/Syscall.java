package run.jvm.emscripten;

import asmble.annotation.WasmName;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Syscall {

    private final Map<Integer, FStream> fds;
    private final Env env;

    public Syscall(Env env) {
        this.fds = new HashMap<>();
        fds.put(1, new FStream.OutputStream(env.out));
        this.env = env;
    }

    @WasmName("__syscall6")
    public int close(int arg0, int arg1) {
        throw new UnsupportedOperationException();
    }

    @WasmName("__syscall54")
    public int ioctl(int which, int varargs) {
        FStream fd = fd(env.getMemory().getInt(varargs));
        IoctlOp op = IoctlOp.byNumber.get(env.getMemory().getInt(varargs + 4));
        Objects.requireNonNull(op);
        switch (op) {
            case TCGETS:
            case TCSETS:
            case TIOCGWINSZ:
                return fd.getTty() == null ? -Errno.ENOTTY.number : 0;
            case TIOCGPGRP:
                if (fd.getTty() == null) return -Errno.ENOTTY.number;
                env.getMemory().putInt(env.getMemory().getInt(varargs + 8), 0);
                return 0;
            case TIOCSPGRP:
                return fd.getTty() == null ? -Errno.ENOTTY.number : -Errno.EINVAL.number;
            case FIONREAD:
                if (fd.getTty() == null) return -Errno.ENOTTY.number;
                throw new UnsupportedOperationException("TODO");
            default:
                throw new EmscriptenException("Unrecognized op: " + op);
        }
    }

    @WasmName("__syscall140")
    public int llseek(int arg0, int arg1) {
        throw new UnsupportedOperationException();
    }

    @WasmName("__syscall146")
    public int writev(int which, int varargs) {
        FStream fd = fd(env.getMemory().getInt(varargs));
        int iov = env.getMemory().getInt(varargs + 4);
        int iovcnt = env.getMemory().getInt(varargs + 8);
        return IntStream.range(0, iovcnt).reduce(0, (total, i) -> {
            int ptr = env.getMemory().getInt(iov + (i * 8));
            int len = env.getMemory().getInt(iov + (i * 8) + 4);
            if (len > 0) fd.write(env.mem.getBulk(ptr, len));
            return total + len;
        });
    }

    private FStream fd(int v) {
        FStream ret = fds.get(v);
        if (ret == null) Errno.EBADF.raise();
        return ret;
    }

    public static enum IoctlOp {
        TCGETS(0x5401),
        TCSETS(0x5402),
        TIOCGPGRP(0x540F),
        TIOCSPGRP(0x5410),
        FIONREAD(0x541B),
        TIOCGWINSZ(0x5413);

        static final Map<Integer, IoctlOp> byNumber;

        static {
            byNumber = Stream.of(values()).collect(Collectors.toMap(IoctlOp::getNumber, Function.identity()));
        }

        final int number;

        IoctlOp(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }
    }
}
