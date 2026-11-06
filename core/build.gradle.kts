import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Platform-independent tracker core: document model, hUGE rules/lowering/export, editor and keymap. The Android app
// consumes the JVM target; linuxX64 is the Kotlin/Native target for the Steam Deck frontend.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // api: @Serializable model classes expose generated serializers to frontends.
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnit() }
