package run.jvm.emscripten;

public abstract class Tty {

    public static class OutputStream extends Tty {
        final java.io.OutputStream out;

        public OutputStream(java.io.OutputStream out) {
            this.out = out;
        }
    }
}
