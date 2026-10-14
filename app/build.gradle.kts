plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sdkFile = file("libs/discord_partner_sdk.aar")
val hasDiscordSdk = sdkFile.isFile
if (System.getenv("REQUIRE_DISCORD_SDK") == "true") {
    check(hasDiscordSdk) { "O build de release exige app/libs/discord_partner_sdk.aar oficial." }
}
val releaseKey = System.getenv("ANDROID_SIGNING_KEYSTORE")
android {
    namespace = "dev.gabriel.switchpresence"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.gabriel.switchpresence"
        minSdk = 26
        targetSdk = 34
        // Stable Releases inject monotonically increasing versions from GitHub Actions.
        // Local/debug builds retain the source version.
        versionCode = System.getenv("APP_RELEASE_CODE")?.toIntOrNull() ?: 9
        versionName = System.getenv("APP_RELEASE_VERSION") ?: "0.1.8"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "HAS_DISCORD_SDK", hasDiscordSdk.toString())
        if (hasDiscordSdk) {
            externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        }
    }
    buildFeatures {
        buildConfig = true
        prefab = hasDiscordSdk
    }
    if (hasDiscordSdk) {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
    signingConfigs {
        if (!releaseKey.isNullOrEmpty()) {
            create("release") {
                storeFile = file(releaseKey)
                storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!releaseKey.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    if (hasDiscordSdk) implementation(files(sdkFile))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
