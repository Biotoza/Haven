plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.glkvm"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // GLKVM is a pure Kotlin HTTPS client; unit tests exercise it over
        // okhttp-mockwebserver on the JVM. Return-defaults keeps the
        // mockable android jar harmless for anything that leaks android.*.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json on the test classpath (the mockable android.jar stub
    // returns null from every JSONObject method under returnDefaultValues).
    // Declared AFTER mockwebserver so it wins the classpath order.
    testImplementation(libs.orgjson)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}