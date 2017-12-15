### Example: C Simple

This shows a simple example of compiling C to WASM and then to the JVM. This is the C version of
[rust-simple](../rust-simple).

In order to run the C or C++ examples, the latest LLVM binaries must be on the `PATH`, built with the experimental
WebAssembly target. This can be built by passing `-DLLVM_EXPERIMENTAL_TARGETS_TO_BUILD=WebAssembly` to `cmake` when
building WebAssembly. Or it can be downloaded from a nightly build site
([this one](http://gsdview.appspot.com/wasm-llvm/builds/) was used for these examples at the time of writing).

Everything else is basically the same as [rust-simple](../rust-simple) except with C code and using `clang`. To run
execute the following from the root `asmble` dir:

    gradlew --no-daemon :examples:c-simple:run