import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// -Pname=value, ~/.gradle/gradle.properties or android/gradle.properties; blank counts as unset.
fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: default
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.lexet"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lexet"
        minSdk = 26
        targetSdk = 36
        versionCode = prop("lexet.versionCode", "2").toInt()   // CI passes the run number
        versionName = prop("lexet.versionName", "0.2.0")

        buildConfigField("String", "API_URL", quoted(prop("lexet.apiUrl", "")))
        buildConfigField("String", "FCM_APP_ID", quoted(prop("lexet.fcm.appId")))
        buildConfigField("String", "FCM_API_KEY", quoted(prop("lexet.fcm.apiKey")))
        buildConfigField("String", "FCM_PROJECT_ID", quoted(prop("lexet.fcm.projectId")))
        buildConfigField("String", "FCM_SENDER_ID", quoted(prop("lexet.fcm.senderId")))
        buildConfigField("String", "FCM_TOPIC", quoted(prop("lexet.fcm.topic", "lexet-et")))
    }

    // Release signing. Supply a keystore to publish updates people can install over
    // earlier versions (Android only accepts updates signed with the same key):
    //   lexet.keystore=/path/to/lexet.jks  lexet.keystorePassword=…  lexet.keyAlias=…  lexet.keyPassword=…
    // Without one, release APKs are signed with the debug key: installable, fine for
    // testing, but not upgradable to a properly signed build later.
    val keystore = prop("lexet.keystore")
    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = prop("lexet.keystorePassword")
                keyAlias = prop("lexet.keyAlias")
                keyPassword = prop("lexet.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // can sit next to the release app on one phone
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = if (keystore.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // shared/taxonomy.json is the single source of truth for codes and bilingual labels.
    sourceSets {
        getByName("main") { assets.srcDir(rootProject.file("../shared")) }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Ship only the app's own languages (libraries bring ~80 translations otherwise).
    androidResources {
        localeFilters += listOf("en", "am")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true   // Robolectric smoke tests
            all { test ->
                // Optional mirror for Robolectric's Android runtime jars: -Probolectric.repo=https://…
                (project.findProperty("robolectric.repo") as String?)?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                }
                test.maxHeapSize = "2g"
            }
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Push is optional at runtime: without FCM keys the app never initialises Firebase.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
