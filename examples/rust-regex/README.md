### Example: Rust Regex

This shows an example of using the Rust regex library on the JVM. This builds on [rust-simple](../rust-simple) and
[rust-string](../rust-string). There is also a simple benchmark checking the performance compared to the built-in Java
regex engine.

#### Main

In this version, we include the `regex` crate. The main loads a ~15k text file Project Gutenberg collection of Mark
Twain works (taken from [this blog post](https://rust-leipzig.github.io/regex/2017/03/28/comparison-of-regex-engines/)
that does Rust regex performance benchmarks). Both the Java and Rust regex engines are abstracted into a common
interface. When run, it checks how many times the word "Twain" appears via both regex engines.

To run it yourself, run the following from the root `asmble` dir:

    gradlew --no-daemon :examples:rust-regex:run

In release mode, the generated class is 903KB w/ ~575 methods. The output:

    'Twain' count in Java: 811
    'Twain' count in Rust: 811

#### Benchmarks

TODO: JMH benchmarks