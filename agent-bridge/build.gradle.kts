import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

val buildCommit =
    System.getenv("PHOCID_BUILD_COMMIT")?.takeIf { it.isNotBlank() }
        ?: runCatching {
                providers
                    .exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }
                    .standardOutput.asText.get()
                    .trim()
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        ?: "unknown"

val buildTime =
    System.getenv("PHOCID_BUILD_TIME")?.takeIf { it.isNotBlank() }
        ?: OffsetDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))

android {
    namespace = "org.sunsetware.phocid.bridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sunsetware.phocid.bridge"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.3.2"

        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    val customKeystorePath = System.getenv("PHOCID_KEYSTORE_PATH")
    if (!customKeystorePath.isNullOrBlank()) {
        signingConfigs.getByName("debug").apply {
            storeFile = file(customKeystorePath)
            storePassword =
                System.getenv("PHOCID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            keyAlias =
                System.getenv("PHOCID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
                    ?: "androiddebugkey"
            keyPassword =
                System.getenv("PHOCID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug").apply {
                enableV2Signing = false
                enableV3Signing = true
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
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
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
}
