package org.junit
// Local stand-in for JUnit 4's @Test (Maven Central is unreachable from the build sandbox).
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
