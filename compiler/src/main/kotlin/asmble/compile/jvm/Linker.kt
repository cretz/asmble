package asmble.compile.jvm

import asmble.annotation.WasmExport
import asmble.annotation.WasmExternalKind
import asmble.annotation.WasmImport
import asmble.annotation.WasmModule
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.invoke.MethodHandle

open class Linker {

    fun link(ctx: Context) {
        // Quick check to prevent duplicate names
        ctx.classes.groupBy { it.name }.values.forEach {
            require(it.size == 1) { "Duplicate module name: ${it.first().name}"}
        }

        // Common items
        ctx.cls.superName = Object::class.ref.asmName
        ctx.cls.version = Opcodes.V1_8
        ctx.cls.access += Opcodes.ACC_PUBLIC
        addConstructor(ctx)
        addDefaultMaxMemField(ctx)

        // Go over each module and add its creation and instance methods
        ctx.classes.forEach {
            addCreationMethod(ctx, it)
            addInstanceField(ctx, it)
            addInstanceMethod(ctx, it)
        }

        TODO()
    }

    fun addConstructor(ctx: Context) {
        // Just the default empty constructor
        ctx.cls.methods.plusAssign(
            Func(
                access = Opcodes.ACC_PUBLIC,
                name = "<init>",
                params = emptyList(),
                ret = Void::class.ref,
                insns = listOf(
                    VarInsnNode(Opcodes.ALOAD, 0),
                    MethodInsnNode(Opcodes.INVOKESPECIAL, Object::class.ref.asmName, "<init>", "()V", false),
                    InsnNode(Opcodes.RETURN)
                )
            ).toMethodNode()
        )
    }

    fun addDefaultMaxMemField(ctx: Context) {
        (Int.MAX_VALUE / Mem.PAGE_SIZE).let { maxAllowed ->
            require(ctx.defaultMaxMemPages <= maxAllowed) {
                "Page size ${ctx.defaultMaxMemPages} over max allowed $maxAllowed"
            }
        }
        ctx.cls.fields.plusAssign(FieldNode(
            // Make it volatile since it will be publicly mutable
            Opcodes.ACC_PUBLIC + Opcodes.ACC_VOLATILE,
            "defaultMaxMem",
            "I",
            null,
            ctx.defaultMaxMemPages * Mem.PAGE_SIZE
        ))
    }

    fun addCreationMethod(ctx: Context, mod: ModuleClass) {
        // The creation method accepts everything needed for import in order of
        // imports. For creating a mod w/ self-built memory, we use a default max
        // mem field on the linkage class if there isn't a default already.
        val params = mod.importClasses(ctx)
        var func = Func(
            access = Opcodes.ACC_PROTECTED,
            name = "create" + mod.name.javaIdent.capitalize(),
            params = params.map(ModuleClass::ref),
            ret = mod.ref
        )
        // The stack here on out is for building params to constructor...

        // The constructor we'll use is:
        // * Mem-class based constructor if it's an import
        // * Max-mem int based constructor if mem is self-built and doesn't have a no-mem-no-max ctr
        // * Should be only single constructor with imports when there's no mem
        val memClassCtr = mod.cls.constructors.find { it.parameters.firstOrNull()?.type?.ref == ctx.mem.memType }
        val constructor = if (memClassCtr == null) mod.cls.constructors.singleOrNull() else {
            // Use the import annotated one if there
            if (memClassCtr.parameters.first().isAnnotationPresent(WasmImport::class.java)) memClassCtr else {
                // If there is a non-int-starting constructor, we want to use that
                val nonMaxMemCtr = mod.cls.constructors.find {
                    it != memClassCtr && it.parameters.firstOrNull()?.type != Integer.TYPE
                }
                if (nonMaxMemCtr != null) nonMaxMemCtr else {
                    // Use the max-mem constructor and put the int on the stack
                    func = func.addInsns(
                        VarInsnNode(Opcodes.ALOAD, 0),
                        FieldInsnNode(Opcodes.GETFIELD, ctx.cls.name, "defaultMaxMem", "I")
                    )
                    mod.cls.constructors.find { it.parameters.firstOrNull()?.type != Integer.TYPE }
                }
            }
        }
        if (constructor == null) error("Unable to find suitable constructor for ${mod.cls}")
        // Now just go over the imports and put them on the stack
        func = constructor.parameters.fold(func) { func, param ->
            param.getAnnotation(WasmImport::class.java).let { import ->
                when (import.kind) {
                    // Invoke the mem handle to get the mem
                    // TODO: for imported memory, fail if import.limit < limits.init * page size at runtime
                    // TODO: for imported memory, fail if import.cap > limits.max * page size at runtime
                    WasmExternalKind.MEMORY -> func.addInsns(
                        VarInsnNode(Opcodes.ALOAD, 1 + params.indexOfFirst { it.name == import.module }),
                        ctx.resolveImportHandle(import).let { memGet ->
                            MethodInsnNode(Opcodes.INVOKEVIRTUAL, memGet.owner, memGet.name, memGet.desc, false)
                        }
                    )
                    // Bind the method
                    WasmExternalKind.FUNCTION -> func.addInsns(
                        LdcInsnNode(ctx.resolveImportHandle(import)),
                        VarInsnNode(Opcodes.ALOAD, 1 + params.indexOfFirst { it.name == import.module }),
                        MethodHandle::bindTo.invokeVirtual()
                    )
                    // Bind the getter
                    WasmExternalKind.GLOBAL -> func.addInsns(
                        LdcInsnNode(ctx.resolveImportHandle(import)),
                        VarInsnNode(Opcodes.ALOAD, 1 + params.indexOfFirst { it.name == import.module }),
                        MethodHandle::bindTo.invokeVirtual()
                    )
                    // Invoke to get handle array
                    // TODO: for imported table, fail if import.size < limits.init * page size at runtime
                    // TODO: for imported table, fail if import.size > limits.max * page size at runtime
                    WasmExternalKind.TABLE -> func.addInsns(
                        VarInsnNode(Opcodes.ALOAD, 1 + params.indexOfFirst { it.name == import.module }),
                        ctx.resolveImportHandle(import).let { tblGet ->
                            MethodInsnNode(Opcodes.INVOKEVIRTUAL, tblGet.owner, tblGet.name, tblGet.desc, false)
                        }
                    )
                }
            }
        }

        // Now with all items on the stack we can instantiate and return
        func = func.addInsns(
            TypeInsnNode(Opcodes.NEW, mod.ref.asmName),
            InsnNode(Opcodes.DUP),
            MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                mod.ref.asmName,
                "<init>",
                constructor.ref.asmDesc,
                false
            ),
            InsnNode(Opcodes.ARETURN)
        )
        ctx.cls.methods.plusAssign(func.toMethodNode())
    }

    fun addInstanceField(ctx: Context, mod: ModuleClass) {
        // Simple protected field  that is lazily populated (but doesn't need to be volatile)
        ctx.cls.fields.plusAssign(
            FieldNode(Opcodes.ACC_PROTECTED, "instance" + mod.name.javaIdent.capitalize(),
                mod.ref.asmDesc, null, null)
        )
    }

    fun addInstanceMethod(ctx: Context, mod: ModuleClass) {
        // The instance method accepts no parameters. It lazily populates a field by calling the
        // creation method. The parameters for the creation method are the imports that are
        // accessed via their instance methods. The entire method is synchronized as that is the
        // most straightforward way to thread-safely lock the lazy population for now.
        val params = mod.importClasses(ctx)
        var func = Func(
            access = Opcodes.ACC_PUBLIC + Opcodes.ACC_SYNCHRONIZED,
            name = mod.name.javaIdent,
            ret = mod.ref
        )
        val alreadyThereLabel = LabelNode()
        func = func.addInsns(
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.name,
                "instance" + mod.name.javaIdent.capitalize(), mod.ref.asmDesc),
            JumpInsnNode(Opcodes.IFNONNULL, alreadyThereLabel),
            VarInsnNode(Opcodes.ALOAD, 0)
        )
        func = params.fold(func) { func, importMod ->
            func.addInsns(
                VarInsnNode(Opcodes.ALOAD, 0),
                MethodInsnNode(Opcodes.INVOKEVIRTUAL, importMod.ref.asmName,
                    importMod.name.javaIdent, importMod.ref.asMethodRetDesc(), false)
            )
        }
        func = func.addInsns(
            FieldInsnNode(Opcodes.PUTFIELD, ctx.cls.name,
                "instance" + mod.name.javaIdent.capitalize(), mod.ref.asmDesc),
            alreadyThereLabel,
            VarInsnNode(Opcodes.ALOAD, 0),
            FieldInsnNode(Opcodes.GETFIELD, ctx.cls.name,
                "instance" + mod.name.javaIdent.capitalize(), mod.ref.asmDesc),
            InsnNode(Opcodes.ARETURN)
        )
        ctx.cls.methods.plusAssign(func.toMethodNode())
    }

    class ModuleClass(val cls: Class<*>, overrideName: String? = null) {
        val name = overrideName ?:
            cls.getDeclaredAnnotation(WasmModule::class.java)?.name ?: error("No module name available for class $cls")
        val ref = TypeRef(Type.getType(cls))

        fun importClasses(ctx: Context): List<ModuleClass> {
            // Try to find constructor with mem class first, otherwise there should be only one
            val constructorWithImports = cls.constructors.find {
                it.parameters.firstOrNull()?.type?.ref == ctx.mem.memType
            } ?: cls.constructors.singleOrNull() ?: error("Unable to find suitable constructor for $cls")
            return constructorWithImports.parameters.toList().mapNotNull {
                it.getAnnotation(WasmImport::class.java)?.module
            }.distinct().map(ctx::namedModuleClass)
        }
    }

    data class Context(
        val classes: List<ModuleClass>,
        val className: String,
        val cls: ClassNode = ClassNode().also { it.name = className.replace('.', '/') },
        val mem: Mem = ByteBufferMem,
        val defaultMaxMemPages: Int = 10
    ) {
        fun namedModuleClass(name: String) = classes.find { it.name == name } ?: error("No module named '$name'")

        fun resolveImportMethod(import: WasmImport) =
            namedModuleClass(import.module).cls.methods.find { method ->
                method.getAnnotation(WasmExport::class.java)?.value == import.field &&
                    method.ref.asmDesc == import.desc
            } ?: error("Unable to find export named '${import.field}' in module '${import.module}'")

        fun resolveImportHandle(import: WasmImport) = resolveImportMethod(import).let { method ->
            Handle(Opcodes.INVOKEVIRTUAL, method.declaringClass.ref.asmName, method.name, method.ref.asmDesc, false)
        }
    }

    companion object : Linker()
}