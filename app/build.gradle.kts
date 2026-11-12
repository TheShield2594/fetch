plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import java.io.File
import java.util.Properties

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

// Environment variables win so CI can inject API keys from repository secrets without a
// local.properties file; a blank variable (an unset secret) counts as missing.
fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return env(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun cmakePath(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    val resolved = if (file.isAbsolute) file else rootProject.file(path)
    return resolved.absolutePath.replace("\\", "/")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK")
)
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun truthy(value: String?): Boolean {
    return value.equals("true", ignoreCase = true) ||
        value.equals("1", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)
}

val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val useLocalFfmpegDecoder = truthy(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: env("USE_LOCAL_FFMPEG_DECODER")
        ?: localProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
val releaseStoreFilePath = env("FETCH_RELEASE_STORE_FILE")
    ?: localProperties.getProperty("FETCH_RELEASE_STORE_FILE")
val releaseKeyAliasValue = env("FETCH_RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("FETCH_RELEASE_KEY_ALIAS")
val releaseKeyPasswordValue = env("FETCH_RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("FETCH_RELEASE_KEY_PASSWORD")
val releaseStorePasswordValue = env("FETCH_RELEASE_STORE_PASSWORD")
    ?: localProperties.getProperty("FETCH_RELEASE_STORE_PASSWORD")
val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseKeyAliasValue != null && releaseKeyPasswordValue != null && releaseStorePasswordValue != null

android {
    namespace = "com.fetch.tv"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.fetch.tv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 24
        targetSdk = 36
        versionCode = 1901
        versionName = "1.9.0"

        buildConfigField("String", "PARENTAL_GUIDE_API_URL", buildConfigString(resolveProperty(devProperties, localProperties, "PARENTAL_GUIDE_API_URL")))
        buildConfigField("String", "INTRODB_API_URL", buildConfigString(resolveProperty(devProperties, localProperties, "INTRODB_API_URL")))
        buildConfigField("String", "TRAILER_API_URL", buildConfigString(resolveProperty(Properties(), localProperties, "TRAILER_API_URL")))
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", buildConfigString(resolveProperty(Properties(), localProperties, "IMDB_RATINGS_API_BASE_URL")))
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", buildConfigString(resolveProperty(Properties(), localProperties, "IMDB_TAPFRAME_API_BASE_URL")))
        buildConfigField("String", "TRAKT_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "TRAKT_CLIENT_ID")))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", buildConfigString(resolveProperty(devProperties, localProperties, "TRAKT_CLIENT_SECRET")))
        buildConfigField("String", "TRAKT_API_URL", "\"${localProperties.getProperty("TRAKT_API_URL", "https://api.trakt.tv/")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", buildConfigString(resolveProperty(devProperties, localProperties, "TRAKT_REDIRECT_URI", "urn:ietf:wg:oauth:2.0:oob")))
        buildConfigField("String", "SIMKL_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_CLIENT_ID")))
        buildConfigField("String", "SIMKL_APP_NAME", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_APP_NAME", "fetch")))
        buildConfigField("String", "MDBLIST_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "MDBLIST_CLIENT_ID")))
        buildConfigField("String", "TMDB_API_KEY", buildConfigString(resolveProperty(devProperties, localProperties, "TMDB_API_KEY")))
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        if (enableDoviNative) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }

        // In-app updater (GitHub Releases)
        buildConfigField("String", "GITHUB_OWNER", "\"TheShield2594\"")
        buildConfigField("String", "GITHUB_REPO", "\"fetch\"")
    }

    if (enableDoviNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = releaseKeyAliasValue
                keyPassword = releaseKeyPasswordValue
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePasswordValue
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = parseBooleanProperty(providers.gradleProperty("debuggable").orNull)
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", buildConfigString(resolveProperty(devProperties, localProperties, "PARENTAL_GUIDE_API_URL")))
            buildConfigField("String", "INTRODB_API_URL", buildConfigString(resolveProperty(devProperties, localProperties, "INTRODB_API_URL")))
            buildConfigField("String", "TRAILER_API_URL", buildConfigString(resolveProperty(devProperties, localProperties, "TRAILER_API_URL")))
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "IMDB_RATINGS_API_BASE_URL")))
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "IMDB_TAPFRAME_API_BASE_URL")))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without release credentials (forks, CI previews) fall back to the debug key.
            signingConfig = if (useDebugReleaseSigning || !hasReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")

            // Production environment (from local.properties; API keys may also come from env vars)
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", buildConfigString(resolveProperty(Properties(), localProperties, "PARENTAL_GUIDE_API_URL")))
            buildConfigField("String", "INTRODB_API_URL", buildConfigString(resolveProperty(Properties(), localProperties, "INTRODB_API_URL")))
            buildConfigField("String", "TRAILER_API_URL", buildConfigString(resolveProperty(Properties(), localProperties, "TRAILER_API_URL")))
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", buildConfigString(resolveProperty(Properties(), localProperties, "IMDB_RATINGS_API_BASE_URL")))
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", buildConfigString(resolveProperty(Properties(), localProperties, "IMDB_TAPFRAME_API_BASE_URL")))
        }
    }

    splits {
        abi {
            isEnable = !buildingAppBundle
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    bundle {
        language {
            // Keep all string resources in the
            // base install so Play Store installs can switch languages at runtime.
            // https://developer.android.com/guide/app-bundle/configure-base
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // CI runs lintFullDebug on every pull request. Errors already in the code are recorded in
    // lint-baseline.xml, so only new ones fail the build; regenerate it with
    // `./gradlew :app:updateLintBaselineFullDebug` after fixing a batch of them. Untranslated
    // strings fall back to English and are filled in later, so they warn instead of failing.
    lint {
        baseline = file("lint-baseline.xml")
        warning += "MissingTranslation"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            // Native libraries stay uncompressed in the APK and load from there, instead of
            // being stored compressed in the APK and extracted again to disk on install (#454).
            // Saves their size in storage on 8 GB sticks; the download is somewhat larger.
            // Nothing in Fetch or its AARs loads a library by path from nativeLibraryDir.
            useLegacyPackaging = false
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // The README screenshot renderer (ReadmeScreenshots, opt-in) loads the app's fonts and strings.
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.fetch.tv.debug")
    }
    // res/xml/searchable.xml can't use manifest placeholders, so the system search provider's
    // authority (the manifest's applicationId + ".search") comes in as a string per variant.
    onVariants { variant ->
        variant.resValues.put(
            variant.makeResValueKey("string", "global_search_authority"),
            variant.applicationId.map { com.android.build.api.variant.ResValue("$it.search", null) }
        )
    }
}

afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) {
            keepRulesConfigurations.add("-keep class j\$.** { *; }")
        }
    }
}

composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "generated/baselineProfiles"
    // No filter: the profiles also cover the library code the journey runs (Kotlin, coroutines,
    // OkHttp, Media3), most of which ships no profile of its own.
}


// kotlin-reflect is left out of the APK: no app code needs it. Tests keep it, since MockK does.
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("UnitTest") && !name.contains("AndroidTest")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
}

dependencies {
    // Platform-free code shared with the desktop app.
    implementation(project(":shared"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    val composeBom = platform(libs.androidx.compose.bom)

    // Source-retention nullness annotations (MonotonicNonNull / RequiresNonNull /
    // EnsuresNonNull) used by the vendored Matroska extractor in
    // com.fetch.tv.core.player.dvmkv. Media3 keeps these compileOnly in its own
    // build, so they aren't on our classpath via the prebuilt AARs.
    compileOnly(libs.checker.qual)

    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Room: watched items (G5); settings stay in DataStore
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 (stock, from Maven)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.extractor)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.container)
    // Guava is used directly (dvmkv extractor), not only through Media3.
    implementation(libs.guava.android)
    implementation(libs.media3.ui)

    // Local decoder AARs (AV1, IAMF, MPEG-H)
    implementation(files(
        "libs/lib-decoder-av1-release.aar",
        "libs/lib-decoder-iamf-release.aar",
        "libs/lib-decoder-mpegh-release.aar"
    ))
    if (useLocalFfmpegDecoder) {
        implementation(project(":ffmpeg-decoder-downmix"))
    } else {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    // libass-android for ASS/SSA subtitle support (from Maven Central)
    implementation(libs.ass.media)
    // Local nextlib-mediainfo fork (static FFmpeg; no libav*.so in final AAR)
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation(libs.mpv.android.lib)

    implementation(libs.gson)

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)

    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)


    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Performance profiling
    implementation(libs.androidx.metrics.performance)  // JankStats (debug builds only, see MainActivity)
    debugImplementation(libs.androidx.compose.runtime.tracing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit)
    // Real org.json for JVM tests; android.jar only ships stubs.
    testImplementation(libs.org.json)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ksp {
    // Room writes each database version's schema here; keep them in git so later versions can
    // be checked against the earlier ones by migration tests.
    arg("room.schemaLocation", "$projectDir/schemas")
}
