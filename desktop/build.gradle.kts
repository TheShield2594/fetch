import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Fetch for desktop: a Compose Multiplatform (JVM) app built on :shared. Linux first;
// Windows and macOS use the same code once their packaging is set up.
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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    testImplementation("junit:junit:4.13.2")
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
