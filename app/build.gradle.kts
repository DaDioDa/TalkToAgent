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
    testOptions {
        unitTests.all {
            it.systemProperty("authorizationVectors", rootProject.file("docs/authorization-vectors.json").absolutePath)
        }
    }
    sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/authorization-test-assets").get().asFile.path)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val authorizationTestAssets = tasks.register<Sync>("authorizationTestAssets") {
    from(rootProject.file("docs/authorization-vectors.json"))
    into(layout.buildDirectory.dir("generated/authorization-test-assets"))
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("AndroidTestAssets")) dependsOn(authorizationTestAssets)
}
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("docs/authorization-vectors.json"))
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.zxing.embedded)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}