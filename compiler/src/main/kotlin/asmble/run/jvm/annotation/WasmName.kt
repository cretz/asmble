package asmble.run.jvm.annotation

@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FUNCTION)
annotation class WasmName(val value: String)