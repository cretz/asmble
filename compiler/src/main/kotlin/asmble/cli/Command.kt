package asmble.cli

import asmble.util.Logger

abstract class Command<T> {

    // Can't have this delegate
    // Ug: http://stackoverflow.com/questions/33966186/how-to-delegate-implementation-to-a-property-in-kotlin
    lateinit var logger: Logger

    abstract val name: String
    abstract val desc: String

    abstract fun args(bld: ArgsBuilder): T
    abstract fun run(args: T)

    fun runWithArgs(bld: ArgsBuilder) = run(args(bld))

    interface ArgsBuilder {

        fun arg(
            name: String,
            desc: String,
            opt: String? = null,
            default: String? = null,
            lowPriority: Boolean = false
        ): String

        fun args(
            name: String,
            desc: String,
            opt: String? = null,
            default: List<String>? = null,
            lowPriority: Boolean = false
        ): List<String>

        fun flag(opt: String, desc: String, lowPriority: Boolean = false): Boolean

        fun done()

        class ActualArgBuilder(var args: List<String>) : ArgsBuilder {

            fun getArg(opt: String?) =
                if (opt != null) args.indexOf("-$opt").takeIf { it != -1 }?.let { index ->
                    args.getOrNull(index + 1)?.also {
                        args = args.subList(0, index) + args.subList(index + 2, args.size)
                    }
                } else args.indexOfFirst { !it.startsWith("-") || it == "--" }.takeIf { it != -1 }?.let { index ->
                    args[index].also { args = args.subList(0, index) + args.subList(index + 1, args.size) }
                }

            override fun arg(name: String, desc: String, opt: String?, default: String?, lowPriority: Boolean) =
                getArg(opt) ?: default ?: error("Arg '$name' not found")

            override fun args(
                name: String,
                desc: String,
                opt: String?,
                default: List<String>?,
                lowPriority: Boolean
            ): List<String> {
                var ret = emptyList<String>()
                while (true) { ret += getArg(opt) ?: break }
                return if (ret.isNotEmpty()) ret else default ?: error("Arg '$name' not found")
            }

            override fun flag(opt: String, desc: String, lowPriority: Boolean) =
                args.indexOf("-$opt").takeIf { it != -1 }?.also {
                    args = args.subList(0, it) + args.subList(it + 1, args.size)
                } != null

            override fun done() =
                require(args.isEmpty()) { "Unknown args: $args" }
        }

        class ArgDefBuilder : ArgsBuilder {
            var argDefs = emptyList<ArgDef>(); private set

            override fun arg(name: String, desc: String, opt: String?, default: String?, lowPriority: Boolean): String {
                argDefs += ArgDef.WithValue(
                    name = name,
                    opt = opt,
                    desc = desc,
                    defaultDesc = default,
                    multi = false,
                    lowPriority = lowPriority
                )
                return default ?: ""
            }

            override fun args(
                name: String,
                desc: String,
                opt: String?,
                default: List<String>?,
                lowPriority: Boolean
            ): List<String> {
                argDefs += ArgDef.WithValue(
                    name = name,
                    opt = opt,
                    desc = desc,
                    defaultDesc = default?.let {
                        if (it.isEmpty()) "<empty>" else if (it.size == 1) it.first() else it.toString()
                    },
                    multi = true,
                    lowPriority = lowPriority
                )
                return default ?: emptyList()
            }

            override fun flag(opt: String, desc: String, lowPriority: Boolean): Boolean {
                argDefs += ArgDef.Flag(
                    opt = opt,
                    desc = desc,
                    multi = false,
                    lowPriority = lowPriority
                )
                return false
            }

            override fun done() { }
        }

        sealed class ArgDef : Comparable<ArgDef> {
            abstract val name: String
            // True means it won't appear in the single-line desc
            abstract val lowPriority: Boolean
            abstract fun argString(bld: StringBuilder): StringBuilder
            abstract fun descString(bld: StringBuilder): StringBuilder

            override fun compareTo(other: ArgDef) = name.compareTo(other.name)

            data class WithValue(
                override val name: String,
                // No opt means trailing
                val opt: String?,
                val desc: String,
                val defaultDesc: String?,
                val multi: Boolean,
                override val lowPriority: Boolean
            ) : ArgDef() {
                override fun argString(bld: StringBuilder): StringBuilder {
                    if (defaultDesc != null) bld.append('[')
                    if (opt != null) bld.append("-$opt ")
                    bld.append("<$name>")
                    if (defaultDesc != null) bld.append(']')
                    if (multi) bld.append("...")
                    return bld
                }

                override fun descString(bld: StringBuilder): StringBuilder {
                    if (opt != null) bld.append("-$opt ")
                    bld.append("<$name>")
                    bld.append(" - $desc ")
                    if (multi) bld.append("Multiple allowed. ")
                    if (defaultDesc != null) bld.append("Optional, default: $defaultDesc")
                    else bld.append("Required.")
                    return bld
                }
            }

            // fun flag(opt: String, desc: String, lowPriority: Boolean = false): Boolean
            data class Flag(
                val opt: String,
                val desc: String,
                val multi: Boolean,
                override val lowPriority: Boolean
            ) : ArgDef() {
                override val name get() = opt

                override fun argString(bld: StringBuilder): StringBuilder {
                    bld.append("[-$opt]")
                    if (multi) bld.append("...")
                    return bld
                }

                override fun descString(bld: StringBuilder): StringBuilder {
                    bld.append("-$opt - $desc Optional. ")
                    if (multi) bld.append("Multiple allowed. ")
                    return bld
                }
            }
        }
    }
}