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

val dotEnvProps = Properties()
val envCandidates = listOf(
    rootProject.file("../.env.local"),
    rootProject.file("../.env"),
    rootProject.file("../env/.env.local"),
    rootProject.file("../env/.env"),
    rootProject.file("../backend/.env.local"),
    rootProject.file("../backend/.env"),
    rootProject.file(".env.local"),
    rootProject.file(".env")
)
envCandidates.forEach { candidate ->
    if (candidate.exists() && candidate.isFile) {
        candidate.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#") && line.contains("=")) {
                val eqIdx = line.indexOf('=')
                val key = line.substring(0, eqIdx).trim().removePrefix("export ").trim()
                val value = line.substring(eqIdx + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                if (key.isNotEmpty() && !dotEnvProps.containsKey(key) && value.isNotBlank()) {
                    dotEnvProps.setProperty(key, value)
                }
            }
        }
    }
}

fun resolveBuildProp(vararg keys: String, default: String = ""): String {
    for (k in keys) {
        localProps.getProperty(k)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        dotEnvProps.getProperty(k)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        (project.findProperty(k) as? String)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        System.getenv(k)?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    }
    return default
}

val backendBaseUrl: String = resolveBuildProp("TUNEMIND_BASE_URL", default = "https://tunemind-backend-iudp.onrender.com")

val spotifyClientId: String = resolveBuildProp("SPOTIFY_CLIENT_ID", default = "dcfad6acbd594477b0705ecc1e6ace3a")

val spotifyClientSecret: String = resolveBuildProp("SPOTIFY_CLIENT_SECRET", default = "134028eff64c4e2cafa4b1f617463650")

val spotifyRedirectUri: String = resolveBuildProp("SPOTIFY_REDIRECT_URI", default = "tunemind://spotify-auth-callback")

val supabaseUrl: String = resolveBuildProp("SUPABASE_URL", "VITE_SUPABASE_URL", "NEXT_PUBLIC_SUPABASE_URL")

val supabaseAnonKey: String = resolveBuildProp("SUPABASE_ANON_KEY", "VITE_SUPABASE_ANON_KEY", "NEXT_PUBLIC_SUPABASE_ANON_KEY", "SUPABASE_KEY")

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
    implementation("androidx.core:core-splashscreen:1.0.1")
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
    implementation(libs.lottie.compose)
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
