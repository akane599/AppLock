package dev.pranav.applock.shizuku

/** Resolve the actual system interface: Android 13 builds have different getTasks signatures. */
internal object TaskQueryCompat {
    fun query(service: Any): Any? {
        val intType = Int::class.javaPrimitiveType
        val booleanType = Boolean::class.javaPrimitiveType
        val signatures = listOf(
            arrayOf(intType, booleanType, booleanType, intType) to arrayOf<Any>(8, false, false, 0),
            arrayOf(intType, booleanType, booleanType) to arrayOf<Any>(8, false, false),
            arrayOf(intType, intType) to arrayOf<Any>(8, 0),
            arrayOf(intType) to arrayOf<Any>(8)
        )
        for ((types, arguments) in signatures) {
            val method = service.javaClass.methods.firstOrNull {
                it.name == "getTasks" && it.parameterTypes.contentEquals(types)
            } ?: continue
            method.isAccessible = true
            // Permission/transport errors must reach the monitor, not become an empty task list.
            return method.invoke(service, *arguments)
        }
        error("Unsupported Android getTasks signature")
    }
}
