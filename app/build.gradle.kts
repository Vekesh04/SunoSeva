import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

// Load local.properties for API key
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val rawOpenRouterKey = localProperties.getProperty("OPENROUTER_API_KEY", "")
val rawOpenAiKey = localProperties.getProperty("OPENAI_API_KEY", "")

val normalizedOpenAiKey = localProperties.getProperty(
    "OPENAI_API_KEY",
    if (rawOpenAiKey.isBlank() && rawOpenRouterKey.startsWith("sk-")) rawOpenRouterKey else rawOpenAiKey
)
val normalizedOpenRouterKey = when {
    rawOpenRouterKey.startsWith("gsk_") -> ""
    rawOpenRouterKey.startsWith("sk-") && rawOpenAiKey.isBlank() -> ""
    else -> rawOpenRouterKey
}
val normalizedGroqKey = localProperties.getProperty(
    "GROQ_API_KEY",
    if (rawOpenRouterKey.startsWith("gsk_")) rawOpenRouterKey else ""
)

android {
    namespace = "com.example.sunoseva"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sunoseva"
        minSdk = 26  // Updated to API 26 as required
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read provider keys from local.properties for security.
        buildConfigField("String", "OPENAI_API_KEY", "\"${normalizedOpenAiKey}\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${normalizedOpenRouterKey}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${normalizedGroqKey}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true  // Enable BuildConfig generation
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")

    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp for networking (logging interceptor for debugging)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
