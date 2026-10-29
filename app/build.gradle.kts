import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Kunci tanda tangan PERMANEN aplikasi Indo HPL (sertifikat SHA-256 97e5c5e2...c51861).
// Kunci TIDAK disimpan di repo (repo publik). Sumbernya, berurutan:
//   1. CI: secret GitHub INDOHPL_KEYSTORE_B64 + INDOHPL_KEYSTORE_PASSWORD (didekode workflow ke INDOHPL_KEYSTORE_FILE)
//   2. Lokal: signing/indohpl.p12 + signing/indohpl.properties (diabaikan git)
// Tanpa kunci ini build tetap jalan (kunci debug acak), tetapi CI tidak menerbitkannya sebagai APK siap pasang.
// JANGAN ganti kunci: kunci berbeda = Android menolak pembaruan dan data hanya bisa dipindah lewat impor CSV.
val signingProps = Properties().apply {
    rootProject.file("signing/indohpl.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val permanentKeystore: File? =
    (System.getenv("INDOHPL_KEYSTORE_FILE")?.let { file(it) } ?: rootProject.file("signing/indohpl.p12")).takeIf { it.exists() }
val permanentPassword: String? =
    System.getenv("INDOHPL_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: signingProps.getProperty("storePassword")

// Nomor versi naik tiap build CI (1000 + nomor run GitHub Actions) supaya Android selalu menganggapnya lebih baru.
val ciRun: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.indohpl.presensi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.indohpl.presensi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1000 + (ciRun ?: 0)
        versionName = ciRun?.let { "2.0.$it" } ?: "2.0.0-lokal"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (permanentKeystore != null && permanentPassword != null) {
            create("permanent") {
                storeFile = permanentKeystore
                storeType = "pkcs12"
                storePassword = permanentPassword
                keyAlias = "indohpl"
                keyPassword = permanentPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("permanent")?.let { signingConfig = it }
        }
        release {
            signingConfigs.findByName("permanent")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.location)

    implementation(libs.androidx.work.runtime.ktx) // pembersihan data lama terjadwal

    testImplementation(libs.junit)
}
