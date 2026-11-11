import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Fetch for desktop: a Compose Multiplatform (JVM) app built on :shared. Linux only for now:
// besides the .deb/.rpm packaging below, the code assumes Linux (libmpv and EGL loaded by their
// Linux names, XDG paths, a Unix-domain socket for deep links, the updater's package formats).
// Windows and macOS would need those ported as well as their own packaging.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

// The desktop app ships with the TV app under the same version, read from app/build.gradle.kts
// (see docs/releasing.md). Packages need a plain MAJOR.MINOR.PATCH, so a pre-release such as
// 1.3.0-beta.2 becomes 1.3.0 with package release 0.beta.2, which sorts before the stable 1.3.0-1.
val appVersionName: String = rootProject.file("app/build.gradle.kts").readText()
    .let { Regex("""versionName\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
    ?: error("Could not read versionName from app/build.gradle.kts")
val desktopVersion = appVersionName.substringBefore('-')
val desktopPackageRelease = appVersionName.substringAfter('-', missingDelimiterValue = "")
    .takeIf { it.isNotEmpty() }
    ?.let { "0." + it.replace('-', '.') }
    ?: "1"
version = appVersionName

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Trakt and Simkl client IDs, as for the TV app: an environment variable (the release workflow
// passes the repository secrets) or the root local.properties. Blank leaves that sign-in hidden.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun credential(name: String, default: String = ""): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: default

val resourceValues = mapOf(
    "version" to appVersionName,
    "traktClientId" to credential("TRAKT_CLIENT_ID"),
    "traktClientSecret" to credential("TRAKT_CLIENT_SECRET"),
    "simklClientId" to credential("SIMKL_CLIENT_ID"),
    "simklAppName" to credential("SIMKL_APP_NAME", "fetch"),
    "tmdbApiKey" to credential("TMDB_API_KEY"),
    "mdblistClientId" to credential("MDBLIST_CLIENT_ID"),
    // The TV's episode ratings services (IMDb by IMDb id, SeriesGraph by TMDB id).
    "imdbTapframeApiBaseUrl" to credential("IMDB_TAPFRAME_API_BASE_URL"),
    "imdbRatingsApiBaseUrl" to credential("IMDB_RATINGS_API_BASE_URL")
)

// Settings › About shows the version; the accounts read their client IDs from here.
tasks.processResources {
    inputs.properties(resourceValues)
    filesMatching("fetch-desktop.properties") { expand(resourceValues) }
    // Appearance › App Font: the TV app's fonts, from its res/font (not duplicated in the repo).
    from(rootProject.file("app/src/main/res/font")) {
        into("fonts")
        include("schibsted_grotesk_variable.ttf", "inter_variable.ttf", "dm_sans_variable.ttf", "opensans_variable.ttf")
    }
    // Appearance › Language: the TV app's strings and their translations, which the desktop
    // looks its English up in (com.fetch.desktop.i18n). Not duplicated in the repo either.
    from(rootProject.file("app/src/main/res")) {
        into("i18n/tv")
        include("values/strings.xml", "values-*/strings.xml")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    // The built-in player talks to libmpv (libmpv2 on Debian/Ubuntu) through JNA.
    implementation(libs.jna)

    testImplementation("junit:junit:4.13.2")
}

// The saved YouTube responses live with the shared parser (shared/src/test/resources/youtube);
// the desktop client's tests replay them too.
sourceSets.test {
    resources.srcDir(rootProject.file("shared/src/test/resources"))
}

compose.desktop {
    application {
        mainClass = "com.fetch.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "fetch"
            packageVersion = desktopVersion
            description = "A Stremio-addon media player"
            vendor = "Fetch"
            licenseFile.set(rootProject.file("LICENSE"))
            // `./gradlew :desktop:suggestRuntimeModules`, plus jdk.crypto.ec, which HTTPS needs on
            // JDK 17 and which jdeps can't see.
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec")

            linux {
                iconFile.set(project.file("icons/fetch.png"))
                packageName = "fetch"
                debMaintainer = "82059300+TheShield2594@users.noreply.github.com"
                menuGroup = "AudioVideo;Video;Player"
                appCategory = "video"
                appRelease = desktopPackageRelease
                shortcut = true
            }
        }
    }
}
