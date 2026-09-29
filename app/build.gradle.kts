plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.talktoagent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.talktoagent"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Opt-in test install alongside an existing differently signed production app.
            if (providers.gradleProperty("bluetoothTestApp").orNull == "true") {
                applicationIdSuffix = ".bluetoothdebug"
                versionNameSuffix = "-bluetooth-debug"
            }
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}