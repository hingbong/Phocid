import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
}

val buildCommit =
    System.getenv("PHOCID_BUILD_COMMIT")?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers
                .exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }
                .standardOutput
                .asText
                .get()
                .trim()
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        ?: "unknown"

// Reproducible builds: never fall back to wall-clock time. See app/build.gradle.kts.
val buildTime =
    System.getenv("PHOCID_BUILD_TIME")?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers
                .exec { commandLine("git", "log", "-1", "--format=%cI", "HEAD") }
                .standardOutput
                .asText
                .get()
                .trim()
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        ?: "unknown"

android {
    namespace = "org.sunsetware.phocid.bridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sunsetware.phocid.bridge"
        minSdk = 35
        targetSdk = 37
        versionCode = 2
        versionName = "0.4.0"

        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    // See app/build.gradle.kts: the debug key must never sign a release channel artifact,
    // because the AGENT_QUERY signature permission is shared with the main app. With no
    // keystore configured the release build stays unsigned (PR verification only).
    val customKeystorePath = System.getenv("PHOCID_KEYSTORE_PATH")
    if (!customKeystorePath.isNullOrBlank()) {
        signingConfigs.getByName("debug").apply {
            storeFile = file(customKeystorePath)
            storePassword =
                System.getenv("PHOCID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            keyAlias =
                System.getenv("PHOCID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
            keyPassword =
                System.getenv("PHOCID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            storeType = "PKCS12"
        }
        signingConfigs.create("release") {
            storeFile = file(customKeystorePath)
            storePassword =
                System.getenv("PHOCID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            keyAlias =
                System.getenv("PHOCID_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
            keyPassword =
                System.getenv("PHOCID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            storeType = "PKCS12"
            enableV2Signing = false
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!customKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // The report-only phase is over: lint findings gate builds again.
        abortOnError = true
        // Snapshot of pre-existing findings; gating applies to new issues only.
        baseline = file("lint-baseline.xml")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

dependencies {
    implementation(project(":agent-api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20260814")
}

// Same bounded-test policy as the app module: a hang must fail with the test name instead
// of stalling the job.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("started", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = TestExceptionFormat.FULL
    }
}

ktfmt { kotlinLangStyle() }
