plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Nomor versi naik otomatis tiap build CI (APP_VERSION_CODE = 100 + nomor run GitHub Actions),
// supaya pembaruan OTA selalu "lebih baru" dari yang terpasang. Build lokal memakai 3 / 1.1.1.
val ciVersionCode: Int? = System.getenv("APP_VERSION_CODE")?.toIntOrNull()

// Kunci tanda tangan tetap: file + kata sandi hanya datang dari secret GitHub (lihat workflow).
// Tanpa kunci tetap, tiap build CI memakai kunci debug acak dan Android menolak memasang pembaruan.
val stableKeystore: File? = System.getenv("SIGNING_KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.exists() }
val stablePassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.indohpl.presensi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.indohpl.presensi"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 3
        versionName = ciVersionCode?.let { "1.2.$it" } ?: "1.1.1"
        vectorDrawables.useSupportLibrary = true
    }

    // Satu basis kode, dua aplikasi. Tiap varian punya applicationId sendiri (bisa dipasang berdampingan),
    // nama aplikasi, warna ikon, dan konfigurasi tempat kerja (src/<varian>/java/.../Site.kt).
    flavorDimensions += "site"
    productFlavors {
        create("indohpl") {
            dimension = "site"
            applicationId = "com.indohpl.presensi"   // aplikasi kasir Indo HPL (tidak berubah)
        }
        create("egkost") {
            dimension = "site"
            applicationId = "com.egkost.presensi"    // aplikasi EG Kost
        }
    }

    signingConfigs {
        if (stableKeystore != null && stablePassword != null) {
            create("stable") {
                storeFile = stableKeystore
                storeType = "pkcs12"
                storePassword = stablePassword
                keyAlias = "presensi"
                keyPassword = stablePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
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
        buildConfig = true // BuildConfig.VERSION_CODE / FLAVOR dipakai pemeriksa pembaruan OTA
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
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

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    // Firestore membawa stub kosong "listenablefuture" yang menutupi kelas asli yang dipakai CameraX.
    implementation(libs.guava)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // org.json di unit test JVM (di HP sudah bawaan Android)
}
