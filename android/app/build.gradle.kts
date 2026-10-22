import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nativeRecipe = rootProject.file("native/Dockerfile")
val recipeText = nativeRecipe.readText()
val i2pdVersion = Regex("(?m)^ARG I2PD_VERSION=(.+)$").find(recipeText)!!.groupValues[1].trim()
val i2pdCommit = Regex("(?m)^ARG I2PD_COMMIT=(.+)$").find(recipeText)!!.groupValues[1].trim()
val supportedAbis = listOf("arm64-v8a", "x86_64")
val nativeAbis = providers.gradleProperty("nativeAbis").orElse(supportedAbis.joinToString(","))
    .get().split(',').map(String::trim).distinct()
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in supportedAbis }) { "Unsupported nativeAbis: $nativeAbis" }

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.multipath.outproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.multipath.outproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
        buildConfigField("String", "I2PD_VERSION", "\"$i2pdVersion\"")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*nativeAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += supportedAbis.filterNot { it in nativeAbis }.map { "**/$it/**" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

val verifyBundledI2pd = tasks.register("verifyBundledI2pd") {
    val patchFile = rootProject.file("../i2pd/my.patch")
    val libraries = file("src/main/jniLibs")
    inputs.files(nativeRecipe, patchFile)
    inputs.dir(libraries)
    inputs.property("nativeAbis", nativeAbis)
    doLast {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val patchHash = digest(patchFile.readText().replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        for (abi in nativeAbis) {
            val directory = libraries.resolve(abi)
            val manifest = directory.resolve("i2pd-build.properties")
            check(manifest.isFile) { "Missing i2pd build manifest for $abi. Run android/build.ps1 or android/build.sh without SkipNative." }
            val metadata = Properties().apply { manifest.inputStream().use { load(it) } }
            val binary = directory.resolve("libi2pd.so")
            check(binary.isFile && metadata.getProperty("version") == i2pdVersion &&
                metadata.getProperty("commit") == i2pdCommit && metadata.getProperty("abi") == abi &&
                metadata.getProperty("patch") == patchHash && metadata.getProperty("binary") == digest(binary.readBytes())) {
                "Stale or modified i2pd binary for $abi. Rebuild the native libraries."
            }
            for (name in listOf("libaggtunnel.so", "libi2pmux.so")) {
                check(directory.resolve(name).isFile) { "Missing $name for $abi. Rebuild the native libraries." }
            }
        }
    }
}

tasks.configureEach {
    if (name == "mergeDebugNativeLibs" || name == "mergeReleaseNativeLibs") dependsOn(verifyBundledI2pd)
}
