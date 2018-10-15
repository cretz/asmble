package asmble.run.jvm

import asmble.ast.Node
import asmble.compile.jvm.*
import asmble.util.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

interface ModuleBuilder<T : Module> {
    fun build(imports: Module.ImportResolver, mod: Node.Module, className: String, name: String?): T

    class Compiled(
        val packageName: String = "",
        val logger: Logger = Logger.Print(Logger.Level.OFF),
        val classLoader: SimpleClassLoader = SimpleClassLoader(Compiled::class.java.classLoader, logger),
        val adjustContext: (ClsContext) -> ClsContext = { it },
        val includeBinaryInCompiledClass: Boolean = false,
        val defaultMaxMemPages: Int = 1
    ) : ModuleBuilder<Module.Compiled> {
        override fun build(
            imports: Module.ImportResolver,
            mod: Node.Module,
            className: String,
            name: String?
        ): Module.Compiled {
            val ctx = ClsContext(
                packageName = packageName,
                className = className,
                mod = mod,
                logger = logger,
                includeBinary = includeBinaryInCompiledClass
            ).let(adjustContext)
            AstToAsm.fromModule(ctx)
            return Module.Compiled(mod, classLoader.fromBuiltContext(ctx), name, ctx.mem, imports, defaultMaxMemPages)
        }

        open class SimpleClassLoader(
            parent: ClassLoader,
            logger: Logger,
            val splitWhenTooLarge: Boolean = true
        ) : ClassLoader(parent), Logger by logger {
            fun fromBuiltContext(ctx: ClsContext): Class<*> {
                trace { "Computing frames for ASM class:\n" + ctx.cls.toAsmString() }
                val writer = if (splitWhenTooLarge) AsmToBinary else AsmToBinary.noSplit
                return writer.fromClassNode(ctx.cls).let { bytes ->
                    debug { "ASM class:\n" + bytes.asClassNode().toAsmString() }
                    val prefix = if (ctx.packageName.isNotEmpty()) ctx.packageName + "." else ""
                    defineClass("$prefix${ctx.className}",  bytes, 0, bytes.size)
                }
            }

            fun addClass(bytes: ByteArray) {
                // Just get the name
                var className = ""
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM5) {
                    override fun visit(a: Int, b: Int, name: String, c: String?, d: String?, e: Array<out String>?) {
                        className = name.replace('/', '.')
                    }
                }, ClassReader.SKIP_CODE)
                defineClass(className, bytes, 0, bytes.size)
            }
        }
    }
}