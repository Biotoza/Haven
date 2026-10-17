// Deliberately dependency-free. LogRedact is needed by leaf modules (serial, SMB,
// Reticulum) that have no project dependencies at all, and making them pull in
// :core:data — Room, DataStore, Hilt — to redact a hostname would be a poor
// trade. Keep it that way: nothing in here should ever need a dependency.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sh.haven.core.redact"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The #518 guard test scans whole-repo source trees at runtime, so Gradle cannot
// infer its inputs. Declare them, or the task serves cached green when a scanned
// file changes (the DesktopManager username leak passed CI for two releases).
// Build outputs sit inside these roots (app/build, termlib/build, ...); without
// the exclusion every compile would invalidate the guard and it would never
// serve an up-to-date result.
tasks.withType<Test>().configureEach {
    val roots = listOf("app", "core", "feature", "termlib").mapNotNull { root ->
        val dir = rootProject.file(root)
        if (dir.exists()) rootProject.fileTree(dir) {
            exclude("**/build", "**/build/**", "**/.gradle", "**/.gradle/**",
                    "**/.kotlin", "**/.kotlin/**")
        } else null
    }
    inputs.files(roots)
}
