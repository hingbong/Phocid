import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

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
                .standardOutput
                .asText
                .get()
                .trim()
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        ?: "unknown"

// Reproducible builds: never fall back to wall-clock time. CI derives the value from the
// commit timestamp; local builds use the same git value so a given commit always yields the
// same BuildConfig (and therefore the same APK bytes for the same inputs).
val buildTime =
    System.getenv("PHOCID_BUILD_TIME")?.takeIf { it.isNotBlank() }
        ?: runCatching {
                providers
                    .exec { commandLine("git", "log", "-1", "--format=%cI", "HEAD") }
                    .standardOutput.asText.get()
                    .trim()
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        ?: "unknown"

// versionCode must grow on every published build, otherwise installed users never receive an
// update. CI supplies the monotonically increasing run number via PHOCID_BUILD_NUMBER.
//
// github.run_number is scoped to the workflow FILE (.github/workflows/android-build.yml):
// renaming or deleting and recreating that file resets the counter, which would roll versionCode
// backwards and block upgrades. Do not rename or rebuild the workflow; if the sequence is ever
// reset anyway, bump baseVersionCode above every previously published value first.
val baseVersionCode = 20260610
val buildNumber = System.getenv("PHOCID_BUILD_NUMBER")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
val computedVersionCode = baseVersionCode + buildNumber
val computedVersionName = "$baseVersionCode.$buildNumber"

android {
    namespace = "org.sunsetware.phocid"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sunsetware.phocid.next"
        minSdk = 35
        targetSdk = 37
        versionCode = computedVersionCode
        versionName = computedVersionName

        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // When a stable keystore is provided, the debug and the dedicated release signing configs
    // both use it, so the app and the agent bridge can be installed over each other and pass
    // the CI signature-consistency check. Without it, debug builds keep AGP's throwaway key and
    // release builds are UNSIGNED (PR verification only): a release channel artifact must never
    // be signed with a debug key, because the AGENT_QUERY signature permission would then be
    // forgeable by anyone holding that keystore. Main-branch CI refuses to build without the
    // keystore (see the workflow's "Validate stable release signing" step).
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
            // Disable v2 signing and force enable v3 signing for modern Android (9+)
            enableV2Signing = false
            enableV3Signing = true
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
        // The report-only phase is over: lint findings gate builds again.
        abortOnError = true
        // Snapshot of pre-existing findings accumulated while lint was report-only; gating
        // applies to new issues only. Regenerate with :app:updateLintBaseline after fixing them.
        baseline = file("lint-baseline.xml")
        // Translation coverage lags behind some locales (see the maintenance backlog);
        // re-enable once coverage is even.
        disable += "MissingTranslation"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
}

dependencies {
    implementation(project(":agent-api"))
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appfunctions)
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
    implementation(libs.re2j)
    implementation(libs.reorderable)
    ksp(libs.androidx.appfunctions.compiler)
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

// A deadlocked unit test would otherwise stall the CI job until the workflow timeout
// cancels it, hiding which test hung. Bound the task and log every test start/finish so
// the last logged test identifies the culprit.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("started", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.register("customSetup") { dependsOn("licenseReleaseReport") }

tasks.preBuild { dependsOn("customSetup") }

// The license test reads the generated report from src/main/assets: depend on it explicitly
// instead of relying on CI invoking :app:customSetup before the test task. AGP registers
// the variant test task only after script evaluation, so the wiring must be lazy: a plain
// tasks.named("testDebugUnitTest") fails at configuration time with 'task not found'.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn("customSetup") }

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