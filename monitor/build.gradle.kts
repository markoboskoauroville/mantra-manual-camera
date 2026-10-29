// v113: MANTRA MONITOR, the camera's sister app. Marko, 2.10.2026: "A sister app is just one NDI monitor which can
// connect to this camera when NDI is enabled ... It reads all the streams from the network ... this camera is just
// giving all the same interface to remote user, but nothing is recorded." Same repository, same release, same key,
// same version number as the camera; it borrows the camera's native bridge and NDI runtime rather than copying them.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appCpp = file("../app/src/main/cpp")
val ndiSdkPresent = File(appCpp, "ndi/include/Processing.NDI.Lib.h").exists()
val appVersion = (project.findProperty("appVersion") as String).toInt()
// v151: the version under the keys, as the camera draws it (version-visible.md); the same code as app/build.gradle.kts
val versionIconDir = layout.buildDirectory.dir("generated/versionIcon").get().asFile
run {
    val segments = mapOf(
        'a' to "M0,0 H5", 'b' to "M5,0 V5", 'c' to "M5,5 V10", 'd' to "M0,10 H5",
        'e' to "M0,5 V10", 'f' to "M0,0 V5", 'g' to "M0,5 H5"
    )
    val digits = mapOf(
        '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
        '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abcdfg"
    )
    val text = "v$appVersion"
    val cell = 5.0
    val gap = 2.2
    val width = text.length * cell + (text.length - 1) * gap
    val left = 54.0 - width / 2
    val top = 64.0
    val paths = StringBuilder()
    text.forEachIndexed { i, ch ->
        val x = left + i * (cell + gap)
        val data = if (ch == 'v') "M0,4 L2.5,10 L5,4"
            else digits[ch].orEmpty().map { segments.getValue(it) }.joinToString(" ")
        // Each glyph's path is written in its 5 x 10 cell and moved into place.
        val moved = Regex("([MLHV])([-0-9.]+)(,([-0-9.]+))?").replace(data) { m ->
            val cmd = m.groupValues[1]
            val a = m.groupValues[2].toDouble()
            val b = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble()
            // Rounded by hand: a Double prints with a dot in every locale.
            fun n(v: Double) = (Math.round(v * 100) / 100.0).toString()
            when (cmd) {
                "H" -> "H" + n(a + x)
                "V" -> "V" + n(a + top)
                else -> cmd + n(a + x) + "," + n((b ?: 0.0) + top)
            }
        }
        paths.append("    <path android:strokeColor=\"#E6E8EA\" android:strokeWidth=\"1.5\" ")
            .append("android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" ")
            .append("android:pathData=\"").append(moved).append("\" />\n")
    }
    val template = file("src/main/icon/ic_monitor_foreground.xml").readText()
    val out = File(versionIconDir, "drawable/ic_monitor_foreground.xml")
    out.parentFile.mkdirs()
    out.writeText(template.replace("    <!--VERSION-->\n", "    <!-- v$appVersion, generated -->\n$paths"))
}

val keystoreFile = rootProject.file("signing/mantra-ndi.p12")
val keystorePassword: String? = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "com.mantraproductions.ndi.monitor"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mantraproductions.ndi.monitor"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()
        buildConfigField("boolean", "NDI_SDK_PRESENT", ndiSdkPresent.toString())
        ndk { abiFilters += listOf("arm64-v8a") }
        if (ndiSdkPresent) externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../shared/kotlin")
            res.srcDirs("src/main/res", versionIconDir)
            jniLibs.srcDirs("../app/src/main/jniLibs")
        }
    }

    if (ndiSdkPresent) {
        externalNativeBuild {
            cmake {
                path = File(appCpp, "CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        create("mantra") {
            if (keystoreFile.exists() && keystorePassword != null) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "mantrandi"
                keyPassword = keystorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        val signing = if (keystoreFile.exists() && keystorePassword != null) signingConfigs.getByName("mantra") else null
        debug { signingConfig = signing }
        release { isMinifyEnabled = false; signingConfig = signing }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // v150 (Marko, 26.10.2026: "Remote controller using ADB, and through ADB you transfer the picture using open-source
    // code from screen copy"): ADB to the camera phone over wireless debugging — pairing by code (SPAKE2), TLS, streams
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // its certificate maker, for the monitor's own ADB key (AdbKeys); the same version libadb-android brings at run time
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    // pairing needs TLS's exported keying material; the system's own Conscrypt hides it (MEASURED on the emulator:
    // NoSuchMethodException com.android.org.conscrypt.Conscrypt.exportKeyingMaterial), this one does not
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
