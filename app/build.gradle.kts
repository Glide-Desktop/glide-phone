plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version from the release tag (CI passes -PglideVersion=1.2.3), else the development default.
val glideVersion: String = (findProperty("glideVersion") as String?)?.removePrefix("v") ?: "1.0"
val glideVersionCode: Int = glideVersion.split('.', '-').take(3).mapIndexed { i, part ->
    (part.toIntOrNull() ?: 0) * when (i) { 0 -> 10000; 1 -> 100; else -> 1 }
}.sum().coerceAtLeast(1)

// Release signing: a real key from the environment (CI secrets or a local shell), otherwise
// the public CI test key in ci/, so builds stay installable and update each other.
val releaseKeystore: String? = System.getenv("GLIDE_KEYSTORE_FILE")?.takeIf { file(it).exists() }

android {
    namespace = "org.mindenit.glidephone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.mindenit.glidephone"
        minSdk = 24
        targetSdk = 37
        versionCode = glideVersionCode
        versionName = glideVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("GLIDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GLIDE_KEY_ALIAS")
                keyPassword = System.getenv("GLIDE_KEY_PASSWORD") ?: System.getenv("GLIDE_KEYSTORE_PASSWORD")
            } else {
                storeFile = rootProject.file("ci/glide-ci-test.keystore")
                storePassword = "glide-ci-test"
                keyAlias = "glide-ci-test"
                keyPassword = "glide-ci-test"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // Apache SSHD jars all ship the same license/notice metadata.
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // SFTP plugin (same Apache SSHD version as KDE Connect)
    implementation(libs.apache.sshd.core)
    implementation(libs.apache.sshd.sftp)
    implementation(libs.apache.sshd.scp)
    implementation(libs.slf4j.api)
    // SFTP over SAF folders on Android 8-10 (SafFileSystem)
    implementation(libs.androidx.documentfile)
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}