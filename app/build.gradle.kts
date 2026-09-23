plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The key a release APK is signed with. Android installs an update only over
// an app signed with the same key, so it is one key for the app's whole life,
// and it is never in this repository: .github/workflows/release.yml writes it
// from the repository's secrets into a temporary file, and a local release
// build finds the same four values in ~/.gradle/gradle.properties.
// Debug builds are signed with the usual debug key and need none of this.
fun releaseSigning(name: String): String? =
    providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull

val releaseKeystore = releaseSigning("SCHALTLI_KEYSTORE")

android {
    namespace = "com.schaltli.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.schaltli.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseSigning("SCHALTLI_KEYSTORE_PASSWORD")
                keyAlias = releaseSigning("SCHALTLI_KEY_ALIAS")
                keyPassword = releaseSigning("SCHALTLI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
        // For VERSION_NAME, which the phone announces as its firmwareVersion
        // - the same field every board fills with its build.
        buildConfig = true
    }

    packaging {
        // The MQTT client's Netty dependencies each ship the same META-INF
        // housekeeping files (index/manifest listings, not actual code) -
        // Android's resource merger can't have duplicates of these, and
        // dropping them is always safe since they're metadata about the
        // *jar*, not anything the app reads at runtime.
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hivemq.mqtt.client)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    debugImplementation(libs.androidx.ui.tooling)

    // JVM unit tests only - no instrumentation, no device. The one thing
    // tested here is the arc rasterizer, which is pure integer arithmetic
    // with no Android dependency at all, and holding it to the designer's
    // own numbers has to be possible on any machine in milliseconds.
    // `org.json` ships with the Android SDK but is stubbed out for local
    // unit tests, so the real implementation is added explicitly rather
    // than every JSON call throwing "not mocked".
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// An unsigned release APK cannot be installed anywhere, and would only be
// found out on a phone. Say so here instead, when one is asked for.
tasks.configureEach {
    if (name == "packageRelease" && releaseKeystore == null) {
        doFirst {
            throw GradleException(
                "No release key: set SCHALTLI_KEYSTORE, SCHALTLI_KEYSTORE_PASSWORD, " +
                    "SCHALTLI_KEY_ALIAS and SCHALTLI_KEY_PASSWORD (environment or " +
                    "~/.gradle/gradle.properties). Debug builds need none of them.",
            )
        }
    }
}
