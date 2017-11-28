### Example: Rust Simple

This shows a simple example of compiling Rust to WASM and then to the JVM.

The [root build script](../../build.gradle) actually has the build commands to build it. But basically it runs
`cargo build --release` on this directory which compiles `add_one` from [lib.rs](src/lib.rs) into
`target/wasm32-unknown-unknown/release/rust_simple.wasm`. Then the build script takes that wasm file and compiles it
to `asmble.generated.RustSimple` in `build/wasm-classes`. The class is used by
[Main.java](src/main/java/asmble/examples/rustsimple/Main.java). It is instantiated with a set of memory and then
`add_one` is invoked with `25` to return `26`.

To run it yourself, run the following from the root `asmble` dir (assuming you have built the Gradle wrapper described
in the root README's "Building and Testing" section):

    ./gradlew --no-daemon :examples:rust-simple:run

Yes, this does include Rust's std lib, but it's not that big of a deal. The actual method executed for `add_one` looks
like this decompiled:

```java
    private int $func0(final int n) {
        return n + 1;
    }
```