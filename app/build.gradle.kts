import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.secrets)
    alias(libs.plugins.google.services)
}

/**
 * Version discipline (P3): `versionName` is the source of truth and `versionCode` derives from it
 * as major*10_000 + minor*100 + patch, so a release cannot ship with a stale code. CI overrides
 * both with `-PversionName=` / `-PversionCode=`; a store rejects any build that reuses a code.
 */
val appVersionName: String = providers.gradleProperty("versionName").orNull ?: "3.0.11"
val appVersionCode: Int = providers.gradleProperty("versionCode").orNull?.toInt()
    ?: appVersionName.split('.').let { parts ->
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 10_000 + minor * 100 + patch
    }

android {
    namespace = "com.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistudio.luxyvpn.kxmzpq"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native cores ship as prebuilt .so. The ABI set is declared once, in `splits.abi`
        // below: declaring it here as well makes `bundleRelease` fail with "Sequence contains
        // more than one matching element" (the bundle task cannot merge two ABI declarations).
    }

    // Do not remove or modify these signingConfigs. They are necessary for building, installing,
    // and updating Android apps in AI Studio.
    signingConfigs {
        create("release") {
            // CI supplies the real upload key through the environment; this repository holds no
            // secrets. Locally a throwaway `my-upload-key.jks` (gitignored) keeps
            // `assembleRelease` runnable so R8 and packaging can be verified without production
            // credentials — it must never be used to publish.
            val envKeystore = System.getenv("KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { file(it) }
                ?.takeIf { it.exists() }
            storeFile = envKeystore ?: file("${rootDir}/my-upload-key.jks").takeIf { it.exists() }
            storePassword = System.getenv("STORE_PASSWORD") ?: "luxydev"
            keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "luxydev"
        }
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Do not remove or modify this signingConfig assignment. It is necessary for Android
            // apps in AI Studio.
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Do not remove or modify this signingConfig assignment. It is necessary for Android
            // apps in AI Studio. Fall back to the standard AGP debug key when debug.keystore is
            // not present (local CLI builds outside AI Studio).
            signingConfig = if (file("${rootDir}/debug.keystore").exists()) {
                signingConfigs.getByName("debugConfig")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // The core AAR also carries x86/x86_64 builds; the app targets physical devices, and
    // dropping those two cuts the universal APK from 147 MB to ~75 MB.
    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    // Two device ABIs ship as separate APKs plus a universal fallback for sideloading.
    // AGP 8.x bug: `PerModuleBundleTask` finds more than one resources APK when ABI splits are
    // on, so `bundleRelease` fails. CI therefore builds the AAB with `-PabiSplits=false`; the
    // AAB carries every ABI anyway and Play does the splitting.
    val abiSplitsEnabled = providers.gradleProperty("abiSplits").orNull
        ?.toBooleanStrictOrNull() ?: true
    splits {
        abi {
            isEnable = abiSplitsEnabled
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
    propertiesFileName = ".env"
    defaultPropertiesFileName = ".env.example"
    ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
    // Xray-core (MPL-2.0) + AndroidLibXrayLite wrapper (LGPL-3.0). See docs/adr/0001.
    implementation(files("libs/libv2ray.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    "ksp"(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
}
