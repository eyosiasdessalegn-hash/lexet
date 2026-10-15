import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: normalisation, calendar, filters, digest, feed mixing, reports.
// No Android dependencies, so it is unit-tested on the JVM and reusable server-side.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
