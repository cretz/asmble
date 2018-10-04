### Example: Go Simple

This runs a simple "Hello, World" in Go. To execute, from the base of the repo run:

    path/to/gradle :examples:go-simple:run

This will create a WASM file in `build/lib.wasm`. Then it will compile it to a JVM class named
`asmble.generated.GoSimple`. Finally it will execute `asmble.examples.gosimple.Main::main` which, with some help from
[go-util](../go-util), will run it. Output is:

    Hello, World