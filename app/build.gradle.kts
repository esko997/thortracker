plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "org.thortracker"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    defaultConfig {
        applicationId = "org.thortracker"
        minSdk = 26
        targetSdk = 32
        versionCode = 13
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.register<JavaExec>("exportSamples") {
    dependsOn("compileDebugUnitTestKotlin")
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
    mainClass.set("org.thortracker.backend.SampleExportTool")
    args(layout.buildDirectory.dir("samples").get().asFile.absolutePath)
}
