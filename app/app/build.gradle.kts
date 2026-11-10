plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

android {
    namespace = "com.diptych.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.diptych.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
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
    }
    sourceSets {
        getByName("test").resources.srcDir(layout.buildDirectory.dir("generated/protocolFixtures"))
    }
    testOptions {
        unitTests.all {
            // SpikeClientSmokeTest only runs with -Pmock=1 (needs tools/mockserver/kh1_mock.py on :7970).
            it.systemProperty("mock", project.findProperty("mock")?.toString() ?: "0")
            it.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
        }
    }
}

// protocol/kh1/fixtures are the single source of truth; copy them into test resources at /fixtures.
val copyProtocolFixtures by tasks.registering(Copy::class) {
    from(rootProject.file("../protocol/kh1/fixtures"))
    into(layout.buildDirectory.dir("generated/protocolFixtures/fixtures"))
}
tasks.matching { it.name.startsWith("process") && it.name.endsWith("UnitTestJavaRes") }
    .configureEach { dependsOn(copyProtocolFixtures) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
