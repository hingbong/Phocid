import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.4.21"
    id("com.ncorti.ktfmt.gradle") version "0.27.0"
    id("com.jaredsburrows.license") version "0.9.91"
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
    namespace = "org.sunsetware.phocid"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sunsetware.phocid.next"
        minSdk = 35
        targetSdk = 37
        versionCode = 20260610
        versionName = "20260610"

        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Use one stable signing key for both debug and release builds when CI provides
    // a keystore. The Android Gradle plugin's debug signing config is reused by the
    // release build type, so both variants can be installed over each other.
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
        debug { isPseudoLocalesEnabled = true }
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug").apply {
                // Disable v2 signing and force enable v3 signing for modern Android (9+)
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
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
    @Suppress("UnstableApiUsage")
    androidResources.generateLocaleConfig = true

    lint {
        // Keep the first runs report-only so the report can be triaged before it gates builds.
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
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // TODO: Maybe requiring a dependency just for `FilenameUtils` is too much
    implementation(libs.commons.io)
    implementation(libs.core.splashscreen)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.jaudiotagger)
    implementation(libs.juniversalchardet)
    implementation(libs.reorderable)
    ksp(libs.androidx.room.compiler)
    //noinspection UseTomlInstead
    implementation("org.sunsetware.omio:omio")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.assertj.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.register("customSetup") { dependsOn("licenseReleaseReport") }

tasks.preBuild { dependsOn("customSetup") }

tasks.assembleUnitTest { dependsOn("customSetup") }

composeCompiler {
    stabilityConfigurationFiles =
        listOf(rootProject.layout.projectDirectory.file("stability_config.conf"))
}

ktfmt { kotlinLangStyle() }

licenseReport {
    // Generate reports
    generateCsvReport = false
    generateHtmlReport = false
    generateJsonReport = true
    generateTextReport = false

    // Copy reports - These options are ignored for Java projects
    copyCsvReportToAssets = false
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = true
    copyTextReportToAssets = false
    useVariantSpecificAssetDirs = false

    // Ignore licenses for certain artifact patterns
    ignoredPatterns = setOf()

    // Show versions in the report - default is false
    showVersions = true
}
