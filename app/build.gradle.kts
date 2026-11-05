import java.io.FileInputStream
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val envGeminiApiKey: String = run {
    // 1. Variable de entorno del sistema (para CI/CD como GitHub Actions)
    System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return@run it }

    // 2. local.properties (ignorado por git en entorno de desarrollo local)
    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localProps.load(FileInputStream(localPropsFile))
        localProps.getProperty("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return@run it }
    }

    // 3. Archivo .env en la raíz del proyecto (ignorado por git)
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("GEMINI_API_KEY=")) {
                val value = trimmed.substringAfter("GEMINI_API_KEY=").trim().trim('"', '\'')
                if (value.isNotBlank()) return@run value
            }
        }
    }
    ""
}

android {
    namespace = "com.example.translator"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.translator"
        minSdk = 24
        targetSdk = 33
        versionCode = 53
        versionName = "1.0.53"

        buildConfigField("String", "GEMINI_API_KEY", "\"$envGeminiApiKey\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("signing/release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)

  // ML Kit
  implementation(libs.mlkit.language.id)
  implementation(libs.mlkit.translate)

  // Updater
  implementation(libs.okhttp)
  implementation(libs.gson)
}
