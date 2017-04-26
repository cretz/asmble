package run.jvm.emscripten;

import java.io.IOException;

public abstract class FStream {
    public abstract Tty getTty();

    public abstract void write(byte[] bytes);

    public static class OutputStream extends FStream {
        private final Tty.OutputStream tty;

        public OutputStream(java.io.OutputStream out) {
            this.tty = new Tty.OutputStream(out);
        }

        @Override
        public Tty.OutputStream getTty() {
            return tty;
        }

        @Override
        public void write(byte[] bytes) {
            try {
                tty.out.write(bytes);
            } catch (IOException e) {
                throw new EmscriptenException(e);
            }
        }
    }
}
