plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
// Release signing is opt-in: tools/release.sh passes -PthortrackerSigningDir=<dir holding thortracker-release.p12 and
// store-password>. Without it, assembleRelease builds an unsigned APK (as the CI build job does).
val signingDir = providers.gradleProperty("thortrackerSigningDir").orNull?.let(::file)
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
        versionCode = 26
        versionName = "0.14.0"
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
    signingConfigs {
        if (signingDir != null) create("release") {
            val password = signingDir.resolve("store-password").readText().trim()
            storeFile = signingDir.resolve("thortracker-release.p12")
            storePassword = password
            keyAlias = "thortracker-release"
            keyPassword = password
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    lint {
        // Sideloaded on handhelds, not published on Google Play; targetSdk 32 is the tested runtime behaviour.
        disable += "ExpiredTargetSdkVersion"
    }
}
dependencies {
    implementation(project(":core"))
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
