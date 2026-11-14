import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { stream ->
        localProps.load(stream)
    }
}
val geminiKey: String = localProps.getProperty("API_KEY")
    ?: localProps.getProperty("GEMINI_API_KEY")
    ?: (project.findProperty("API_KEY") as? String)
    ?: (project.findProperty("GEMINI_API_KEY") as? String)
    ?: System.getenv("API_KEY")
    ?: System.getenv("GEMINI_API_KEY")
    ?: ""

val backendBaseUrl: String = localProps.getProperty("TUNEMIND_BASE_URL")
    ?: (project.findProperty("TUNEMIND_BASE_URL") as? String)
    ?: System.getenv("TUNEMIND_BASE_URL")
    ?: "https://tunemind-backend-iudp.onrender.com"

val spotifyClientId: String = localProps.getProperty("SPOTIFY_CLIENT_ID")
    ?: (project.findProperty("SPOTIFY_CLIENT_ID") as? String)
    ?: System.getenv("SPOTIFY_CLIENT_ID")
    ?: "dcfad6acbd594477b0705ecc1e6ace3a"

val spotifyClientSecret: String = localProps.getProperty("SPOTIFY_CLIENT_SECRET")
    ?: (project.findProperty("SPOTIFY_CLIENT_SECRET") as? String)
    ?: System.getenv("SPOTIFY_CLIENT_SECRET")
    ?: "134028eff64c4e2cafa4b1f617463650"

val spotifyRedirectUri: String = localProps.getProperty("SPOTIFY_REDIRECT_URI")
    ?: (project.findProperty("SPOTIFY_REDIRECT_URI") as? String)
    ?: System.getenv("SPOTIFY_REDIRECT_URI")
    ?: "tunemind://spotify-auth-callback"

val supabaseUrl: String = localProps.getProperty("SUPABASE_URL")
    ?: (project.findProperty("SUPABASE_URL") as? String)
    ?: System.getenv("SUPABASE_URL")
    ?: ""

val supabaseAnonKey: String = localProps.getProperty("SUPABASE_ANON_KEY")
    ?: (project.findProperty("SUPABASE_ANON_KEY") as? String)
    ?: System.getenv("SUPABASE_ANON_KEY")
    ?: ""

val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { stream ->
        keystoreProps.load(stream)
    }
}
val releaseStoreFilePath: String? = (keystoreProps.getProperty("storeFile")
    ?: System.getenv("TUNEMIND_KEYSTORE_FILE"))?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? = (keystoreProps.getProperty("storePassword")
    ?: System.getenv("TUNEMIND_KEYSTORE_PASSWORD"))?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = (keystoreProps.getProperty("keyAlias")
    ?: System.getenv("TUNEMIND_KEY_ALIAS"))?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = (keystoreProps.getProperty("keyPassword")
    ?: System.getenv("TUNEMIND_KEY_PASSWORD"))?.takeIf { it.isNotBlank() }
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "com.tunemind.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.tunemind.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TUNEMIND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"$spotifyClientSecret\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"$spotifyRedirectUri\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Room schema export keeps migration history in version control.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.gson)
    implementation(libs.androidx.media)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))

    // Testing
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
