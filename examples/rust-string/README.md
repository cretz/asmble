### Example: Rust String

This shows an example of using Rust strings on the JVM. This builds on [rust-simple](../rust-simple).

In this version, we do allocation and deallocation on the Rust side and we have a Java pointer object that deallocates
on finalization. Inside of Rust, we make sure not to take ownership of any of the data. To demonstrate string use, we
implement two functions on the Rust side: one for string length and another for prepending "From Rust: ". Both don't
take strings directly, but instead pointers and lengths to the byte arrays.

To run it yourself, run the following from the root `asmble` dir:

    gradlew --no-daemon :examples:rust-string:run

In release mode, the generated class is 128KB w/ a bit over 200 methods, but it is quite fast. The output:

    Char count of 'tester': 6
    Char count of Russian hello (Здравствуйте): 12
    From Rust: Hello, World!

For me on Windows, the Russian word above just appears as `????????????`, but the rest is right.