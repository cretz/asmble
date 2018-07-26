# Asmble

Asmble is a compiler that compiles [WebAssembly](http://webassembly.org/) code to JVM bytecode. It also contains
utilities for working with WASM code from the command line and from JVM languages.

## Quick Start

WebAssembly by itself does not have routines for printing to stdout or any external platform features. For this
example we'll use the test harness used by the [spec](https://github.com/WebAssembly/spec/). Java 8 must be installed.

Download the latest TAR/ZIP from the [releases](https://github.com/cretz/asmble/releases) area and extract it to
`asmble/`.

WebAssembly code is either in a [binary file](http://webassembly.org/docs/binary-encoding/) (i.e. `.wasm` files) or a
[text file](http://webassembly.org/docs/text-format/) (i.e. `.wast` files). The following code imports the `print`
function from the test harness. Then it creates a function calling `print` for the integer 70 and sets it to be called
on module init:

```
(module
  (import "spectest" "print" (func $print (param i32)))
  (func $print70 (call $print (i32.const 70)))
  (start $print70)
)
```

Save this as `print-70.wast`. Now to run this, execute:

    ./asmble/bin/asmble run -testharness print-70.wast

The result will be:

    70 : i32

Which is how the test harness prints an integer. See the [examples](examples) directory for more examples.

## CLI Usage

Assuming Java 8 is installed, download the latest [release](https://github.com/cretz/asmble/releases) and extract it.
The `asmble` command is present in the `asmble/bin` folder. There are multiple commands in Asmble that can be seen by
executing `asmble` with no commands:

    Usage:
      COMMAND options...

    Commands:
      compile - Compile WebAssembly to class file
      help - Show command help
      invoke - Invoke WebAssembly function
      run - Run WebAssembly script commands
      translate - Translate WebAssembly from one form to another

    For detailed command info, use:
      help COMMAND

### Compiling

Running `asmble help compile`:

    Command: compile
    Description: Compile WebAssembly to class file
    Usage:
      compile <inFile> [-format <inFormat>] <outClass> [-out <outFile>]

    Args:
      <inFile> - The wast or wasm WebAssembly file name. Can be '--' to read from stdin. Required.
      -format <inFormat> - Either 'wast' or 'wasm' to describe format. Optional, default: <use file extension>
      -log <logLevel> - One of: trace, debug, info, warn, error, off. Optional, default: warn
      <outClass> - The fully qualified class name. Required.
      -out <outFile> - The file name to output to. Can be '--' to write to stdout. Optional, default: <outClass.class>

This is used to compile WebAssembly to a class file. See the [compilation details](#compilation-details) for details about how
WebAssembly translates to JVM bytecode. The result will be a `.class` file containing JVM bytecode.

NOTE: There is no runtime required with the class files. They are self-contained.

### Invoking

Running `asmble help invoke`:

    Command: invoke
    Description: Invoke WebAssembly function
    Usage:
      invoke [-in <inFile>]... [-reg <registration>]... [-mod <module>] [<export>] [<arg>]...

    Args:
      <arg> - Parameter for the export if export is present. Multiple allowed. Optional, default: <empty>
      -defmaxmempages <defaultMaxMemPages> - The maximum number of memory pages when a module doesn't say. Optional, default: 5
      <export> - The specific export function to invoke. Optional, default: <start-func>
      -in <inFile> - Files to add to classpath. Can be wasm, wast, or class file. Named wasm/wast modules here are automatically registered unless -noreg is set. Multiple allowed. Optional, default: <empty>
      -log <logLevel> - One of: trace, debug, info, warn, error, off. Optional, default: warn
      -mod <module> - The module name to run. If it's a JVM class, it must have a no-arg constructor. Optional, default: <last-in-entry>
      -noreg - If set, this will not auto-register modules with names. Optional.
      -reg <registration> - Register class name to a module name. Format: modulename=classname. Multiple allowed. Optional, default: <empty>
      -res - If there is a result, print it. Optional.
      -testharness - If set, registers the spec test harness as 'spectest'. Optional.

This can run WebAssembly code including compiled `.class` files. For example, put the following WebAssembly at
`add-20.wast`:

```
(module
  (func (export "doAdd") (param $i i32) (result i32)
    (i32.add (get_local 0) (i32.const 20))
  )
)
```

This can be invoked via the following with the result shown:

    asmble invoke -res -in add-20.wast doAdd 100

That will print `120`. However, it can be compiled first like so:

    asmble compile add-20.wast MyClass

Now there is a file called `MyClass.class`. Since it has a no-arg constructor because it doesn't import anything (see
[compilation details](#compilation-details) below), it can be invoked as well:

    asmble invoke -res -in MyClass.class -reg myMod=MyClass -mod myMod doAdd 100

Note, that any Java class can be registered for the most part. It just needs to have a no-arg consstructor and any
referenced functions need to be public, non-static, and with return/param types of only int, long, float, or double.

### Running Scripts

The WebAssembly spec has a concept of [scripts](https://github.com/WebAssembly/spec/tree/master/interpreter#scripts) for
testing purposes. Running `asmble help run`:

    Command: run
    Description: Run WebAssembly script commands
    Usage:
      run [-in <inFile>]... [-reg <registration>]... <scriptFile>

    Args:
      -defmaxmempages <defaultMaxMemPages> - The maximum number of memory pages when a module doesn't say. Optional, default: 5
      -in <inFile> - Files to add to classpath. Can be wasm, wast, or class file. Named wasm/wast modules here are automatically registered unless -noreg is set. Multiple allowed. Optional, default: <empty>
      -log <logLevel> - One of: trace, debug, info, warn, error, off. Optional, default: warn
      -noreg - If set, this will not auto-register modules with names. Optional.
      -reg <registration> - Register class name to a module name. Format: modulename=classname. Multiple allowed. Optional, default: <empty>
      <scriptFile> - The script file to run all commands for. This can be '--' for stdin. Must be wast format. Required.
      -testharness - If set, registers the spec test harness as 'spectest'. Optional.

So take something like the
[start.wast](https://github.com/WebAssembly/spec/blob/6a01dab6d29b7c2b5dfd3bb3879bbd6ab76fd5dc/test/core/start.wast)
test case from the spec and run it with the test harness:

    asmble run -testharness start.wast

And confirm it returns the
[expected output](https://github.com/WebAssembly/spec/blob/6a01dab6d29b7c2b5dfd3bb3879bbd6ab76fd5dc/test/core/expected-output/start.wast.log).

The comments concerning importing Java classes for "invoke" apply here too.

### Translating

Running `asmble help translate`:

    Command: translate
    Description: Translate WebAssembly from one form to another
    Usage:
      translate <inFile> [-in <inFormat>] [<outFile>] [-out <outFormat>]

    Args:
      -compact - If set for wast out format, will be compacted. Optional.
      <inFile> - The wast or wasm WebAssembly file name. Can be '--' to read from stdin. Required.
      -in <inFormat> - Either 'wast' or 'wasm' to describe format. Optional, default: <use file extension>
      -log <logLevel> - One of: trace, debug, info, warn, error, off. Optional, default: warn
      <outFile> - The wast or wasm WebAssembly file name. Can be '--' to write to stdout. Optional, default: --
      -out <outFormat> - Either 'wast' or 'wasm' to describe format. Optional, default: <use file extension or wast for stdout>

Asmble can translate `.wasm` files to `.wast` or vice versa. It can also translate `.wast` to `.wast` which has value
because it resolves all names and creates a more raw yet deterministic and sometimes more readable `.wast`. Technically,
it can translate `.wasm` to `.wasm` but there is no real benefit.

All Asmble is doing internally here is converting to a common AST regardless of input then writing it out in the desired
output.

## Programmatic Usage

Asmble is written in Kotlin but since Kotlin is a thin layer over traditional Java, it can be used quite easily in all
JVM languages.

### Getting

The compiler and annotations are deployed to Maven Central. The compiler is written in Kotlin and can be added as a
Gradle dependency with:

    compile 'com.github.cretz.asmble:asmble-compiler:0.3.0'

This is only needed to compile of course, the compiled code has no runtime requirement. The compiled code does include
some annotations (but in Java its ok to have annotations that are not found). If you do want to reflect the annotations,
the annotation library can be added as a Gradle dependency with:

    compile 'com.github.cretz.asmble:asmble-annotations:0.3.0'

### Building and Testing

To manually build, clone the repository:

    git clone --recursive https://github.com/cretz/asmble

The reason we use recursive is to clone the spec submodule we have embedded at `src/test/resources/spec`. Then, with
[gradle](https://gradle.org/) installed, navigate to the cloned repository and create the gradle wrapper via
`gradle wrapper`. Now the `gradlew` command is available.

To build, run `./gradlew build`. This will run all tests which includes the test suite from the WebAssembly spec.
Running `./gradlew assembleDist` builds the same zip and tar files uploaded to the releases area.

### Library Notes

The API documentation is not yet available at this early stage. But as an overview, here are some interesting classes
and packages:

* `asmble.ast.Node` - All WebAssembly AST nodes as static inner classes.
* `asmble.cli` - All code for the CLI.
* `asmble.compile.jvm.AstToAsm` - Entry point to go from AST module to [ASM](http://asm.ow2.org/) ClassNode.
* `asmble.compile.jvm.Mem` - Interface that can be implemented to change how memory is handled. Right now
  `ByteBufferMem` in the same package is the only implementation and it emits `ByteBuffer`.
* `FuncBuilder` - Where the bulk of the WASM-instruction-to-JVM-instruction translation happens.
* `asmble.io` - Classes for translating to/from ast nodes, bytes (i.e. wasm), sexprs (i.e. wast), and strings.
* `asmble.run.jvm` - Tools for running WASM code on the JVM. Specifically `ScriptContext` which helps with linking.

And for those reading code, here are some interesting algorithms:

* `asmble.compile.jvm.RuntimeHelpers#bootstrapIndirect` (in Java, not Kotlin) - Manipulating arguments to essentially
  chain `MethodHandle` calls for an `invokedynamic` bootstrap. This is actually taken from the compiled Java class and
  injected as a synthetic method of the module class if needed.
* `asmble.compile.jvm.InsnReworker#addEagerLocalInitializers` - Backwards navigation up the instruction list to make
  sure that a local is set before it is get.
* `asmble.compile.jvm.InsnReworker#injectNeededStackVars` - Inject instructions at certain places to make sure we have
  certain items on the stack when we need them.
* `asmble.io.ByteReader$InputStream` - A simple eof-peekable input stream reader.

## Compilation Details

Asmble does its best to compile WASM ops to JVM bytecodes with minimal overhead. Below are some details on how each part
is done. Every module is represented as a single class. This section assumes familiarity with WebAssembly concepts.

#### Constructors

Asmble creates different constructors based on the memory requirements. Each constructor created contains the imports as
parameters (see [imports](#imports) below)

If the module does not define memory, a single constructor is created that accepts all other imports. If the module does
define memory, two constructors are created: one accepting a memory instance, and an overload that instead accepts an
integer value for max memory that is used to create the memory instance before sending to the first one. If the maximum
memory is given for the module, a third constructor is created without any memory parameters and just calls the max
memory overload w/ the given max memory value. All three of course have other imports as the rest of the parameters.

After all other constructor duties (described in sections below), the module's start function is called if present.

#### Memory

Memory is built or accepted in the constructor and is stored in a field. The current implementation uses a `ByteBuffer`.
Since `ByteBuffer`s are not dynamically growable, the max memory is an absolute max even though there is a limit which
is adjusted on `grow_memory`. Any data for the memory is set in the constructor.

#### Table

In the WebAssembly MVP a table is just a set of function pointers. This is stored in a field as an array of
`MethodHandle` instances. Any elements for the table are set in the constructor.

#### Globals

Globals are stored as fields on the class. A non-import global is simply a field that is final if not mutable. An import
global is a `MethodHandle` to the getter and a `MethodHandle` to the setter if mutable. Any values for the globals are
set in the constructor.

#### Imports

The constructor accepts all imports as params. Memory is imported via a `ByteBuffer` param, then function
imports as `MethodHandle` params, then global imports as `MethodHandle` params (one for getter and another for setter if
mutable), then a `MethodHandle` array param for an imported table. All of these values are set as fields in the
constructor.

#### Exports

Exports are exported as public methods of the class. The export names are mangled to conform to Java identifier
requirements. Function exports are as is whereas memory, global, and table exports have the name capitalized and are
then prefixed with "get" to match Java getter conventions.

Exports are always separate methods instead of just changing the name of an existing method or field. This encapsulation
allows things like many exports for a single item.

#### Types

WebAssembly has 4 types: `i32`, `i64`, `f32`, and `f64`. These translate quite literally to `int`, `long`, `float`, and
`double` respectively.

#### Control Flow Operations

Operations such as `unreachable` (which throws) behave mostly as expected. Branching and looping are handled with jumps.
The problem that occurs with jumping is that WebAssembly does not require compiler writers to clean up their own stack.
Therefore, if the WASM ops have extra stack values, we pop it before jumping which has performance implications but not
big ones. For most sane compilers, the stack will be managed stringently and leftover stack items will not be present.

Luckily, `br_table` jumps translate literally to JVM table switches which makes them very fast. There is a special set
of code for handling really large tables (because of Java's method limit) but this is unlikely to affect most in
practice.

#### Call Operations

Normal `call` operations do different things depending upon whether it is an import or not. If it is an import, the
`MethodHandle` is retrieved from a field and called via `invokeExact`. Otherwise, a normal `invokevirtual` is done to
call the local method.

A `call_indirect` is done via `invokedynamic` on the JVM. Specifically, `invokedynamic` specifies a synthetic bootstrap
method that we create. It does a one-time call on that bootstrap method to get a `MethodHandle` that can be called in
the future. We wouldn't normally have to use `invokedynamic` because we could use the index to reference a
`MethodHandle` in the array field. However, in WebAssembly, that index is *after* the parameters of the call and the
stack manipulation we would have to do would be far too expensive.

So we need a MethodHandle that takes the params of the target method, and *then* the index, to make the call. But we
also need "this" because it is expected at some point in the future that the table field could be changed underneath and
we don't want that field reference to be cached via the one-time bootstrap call. We do this with a synthetic bootstrap
method which uses some `MethodHandle` trickery to manipulate it the way we want. This makes indirect calls very fast,
especially on successive invocations.

#### Parametric Operations

A `drop` translates literally to a `pop`. A select translates to a conditional swap, then a pop.

#### Variable Access

Local variable access translates fairly easily because WebAssembly and the JVM treat the concept of parameters as the
initial locals similarly. Granted the JVM form has "this" at slot 0. Also, WebAssembly doesn't treat 64-bit vars as 2
slots like the JVM, so some simple math is done like it is with the stack.

WebAssembly requires all locals the assume they are 0 whereas the JVM requires locals be set before use. An algorithm in
Asmble makes sure that locals are set to 0 before they are fetched in any situation where they weren't explicitly set
first.

Global variable access depends on whether it's an import or not. Imports call getter `MethodHandle`s whereas non-imports
simply do normal field access.

#### Memory Operations

Memory operations are done via `ByteBuffer` methods on a little-endian buffer. All operations including unsigned
operations are tailored to use specific existing Java stdlib functions.

As a special optimization, we put the memory instance as a local var if it is accessed a lot in a function. This is
cheaper than constantly fetching the field.

#### Number Operations

Constants are simply `ldc` bytecode ops on the JVM. Comparisons are done via specific bytecodes sometimes combined with
JVM calls for things like unsigned comparison. Operators use idiomatic JVM approaches as well.

The WebAssembly spec requires a runtime check of overflow during `trunc` calls. This is enabled by default in Asmble. It
defers to an internal synthetic method that does the overflow check. This can be programmatically disabled for better
performance.

#### Stack

Asmble maintains knowledge of types on the stack during compilation and fails compilation for any invalid stack items.
This includes the somewhat complicated logic concerning unreachable code.

In several cases, Asmble needs something on the stack that WebAssembly doesn't, such as "this" before the value of a
`putfield` call when setting a non-import global. In order to facilitate this, Asmble does a preprocessing of the
instructions. It builds the stack diffs and injects the needed items (e.g. a reference to the memory class for a load)
at the right place in the instruction list to make sure they are present when needed.

As an unintended side effect of this kind of logic, it turns out that Asmble never needs local variables beyond what
WebAssembly specifies. No temp variables or anything. It could be argued however that the use of temp locals might make
some of the compilation logic less complicated and could even improve runtime performance in places where we overuse the
stack (e.g. some places where we do a swap).

## Caveats

Below are some performance and implementation quirks where there is a bit of an impedance mismatch between WebAssembly
and the JVM:

* WebAssembly has a nice data section for byte arrays whereas the JVM does not. Right now we use a single-byte-char
  string constant (i.e. ISO-8859 charset). This saves class file size, but this means we call `String::getBytes` on
  init to load bytes from the string constant. Due to the JVM using an unsigned 16-bit int as the string constant
  length, the maximum byte length is 65536. Since the string constants are stored as UTF-8 constants, they can be up to
  four bytes a character. Therefore, we populate memory in data chunks no larger than 16300 (nice round number to make
  sure that even in the worse case of 4 bytes per char in UTF-8 view, we're still under the max).
* The JVM makes no guarantees about trailing bits being preserved on NaN floating point representations like WebAssembly
  does. This causes some mismatch on WebAssembly tests depending on how the JVM "feels" (I haven't dug into why some
  bit patterns stay and some don't when NaNs are passed through methods).
* The JVM requires strict stack management where the compiler writer is expected to pop off what he doesn't use before
  performing unconditional jumps. WebAssembly requires the runtime to discard unused stack items before unconditional
  jump so we have to handle this. This can cause performance issues because essentially we do a "pop-before-jump" which
  pops all unneeded stack values before jumping. If the target of the jump expects a fresh item on the stack (i.e. a
  typed block) then it gets worse because we have to pop what we don't need *except* for the last stack value which
  leads to a swap-pop-and-swap. Hopefully in real world use, tools that compile to WebAssembly don't have a bunch of
  these cases. If they do, we may need to look into spilling to temporary local vars.
* Both memory and tables have "max capacity" and "initial capacity". While memory uses a `ByteBuffer` which has these
  concepts (i.e. "capacity" and "limit"), tables use an array which only has the "initial capacity". This means that
  tests that check for max capacity on imports at link time do not fail because we don't store max capacity for a table.
  This is not a real problem for the MVP since the table cannot be grown. But once it can, we may need to consider
  bringing another int along with us for table max capacity (or at least make it an option).
* WebAssembly has a concept of "unset max capacity" which means there can theoretically be an infinite capacity memory
  instance. `ByteBuffer`s do not support this, but care is taken to allow link time and runtime max memory setting to
  give the caller freedom.
* WebAssembly requires some trunc calls to do overflow checks, whereas the JVM does not. So for example, WebAssembly
  has `i32.trunc_s/f32` which would usually be a simple `f2i` JVM instruction, but we have to do an overflow check that
  the JVM does not do. We do this via a private static synthetic method in the module. There is too much going on to
  inline it in the method and if several functions need it, it can become hot and JIT'd. This may be an argument for a
  more global set of runtime helpers, but we aim to be runtime free. Care was taken to allow the overflow checks to be
  turned off programmatically.
* WebAssembly allows unsigned 32 bit int memory indices. `ByteBuffer` only has signed which means the value can
  overflow. And in order to support even larger sets of memory, WebAssembly supports constant offsets which are added
  to the runtime indices. Asmble will eagerly fail compilation if an offset is out of range. But at runtime we don't
  check by default and the overflow can wrap around and access wrong memory. There is an option to do the overflow check
  when added to the offset which is disabled by default. Other than this there is nothing we can do easily.

## FAQ

**Why?**

I like writing compilers and I needed a sufficiently large project to learn Kotlin really well to make a reasonable
judgement on it. I also wanted to become familiar w/ WebAssembly. I don't really have a business interest for this and
therefore I cannot promise it will forever be maintained.

**Will it work on Android?**

I have not investigated. But I do use `invokedynamic` and `MethodHandle` so it would need to be a modern version of
Android. I assume, then, that both runtime and compile-time code might run there. Experiment feedback welcome.

**What about JVM to WASM?**

I'll be watching the GC approach taken and then reevaluate options. Everyone is focused on targeting WASM with several
languages but is missing the big problem: lack of a standard library. There is not a lot of interoperability between
WASM compiled from Rust, C, Java, etc if e.g. they all have their own way of handling strings. Someone needs to build a
definition of an importable set of modules that does all of these things, even if it's in WebIDL. I dunno, maybe the
effort is already there, I haven't really looked.

There is https://github.com/konsoletyper/teavm

**So I can compile something in C via Emscripten and have it run on the JVM with this?**

Yes, but work is required. WebAssembly is lacking any kind of standard library. So Emscripten will either embed it or
import it from the platform (not sure which/where, I haven't investigated). It might be a worthwhile project to build a
libc-of-sorts as Emscripten knows it for the JVM. Granted it is probably not the most logical approach to run C on the
JVM compared with direct LLVM-to-JVM work.

**Debugging?**

Not yet, once source maps get standardized I may revisit.

## TODO

* Add "dump" that basically goes from WebAssembly to "javap" like output so details are clear
* Expose the advanced compilation options
* Add "link" command that will build an entire JAR out of several WebAssembly files and glue code between them
* Annotations to make it clear what imports are expected
* Compile to JS and native with Kotlin
* Add javax.script (which can give things like a free repl w/ jrunscript)