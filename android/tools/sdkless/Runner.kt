import java.io.File
// Minimal reflective test runner: runs every @org.junit.Test method in the given classes.
fun main(args: Array<String>) {
    var passed = 0; val failures = mutableListOf<String>()
    for (cn in args) {
        val cls = Class.forName(cn)
        for (m in cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }) {
            val inst = cls.getDeclaredConstructor().newInstance()
            try { m.invoke(inst); passed++; println("PASS ${cls.simpleName}.${m.name}") }
            catch (e: java.lang.reflect.InvocationTargetException) { failures += "${cls.simpleName}.${m.name}: ${e.targetException}"; println("FAIL ${cls.simpleName}.${m.name}: ${e.targetException}") }
        }
    }
    println("\n$passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
