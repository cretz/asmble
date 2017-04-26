package run.jvm.emscripten;

public class EmscriptenException extends RuntimeException {

    public EmscriptenException() {
    }

    public EmscriptenException(String message) {
        super(message);
    }

    public EmscriptenException(String message, Throwable cause) {
        super(message, cause);
    }

    public EmscriptenException(Throwable cause) {
        super(cause);
    }
}
