plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.schaltli.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.schaltli.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
