// Platform-free Kotlin/JVM code shared by the Android TV app (:app) and the desktop app
// (:desktop): the Stremio addon protocol (DTOs, mappers, Retrofit API), the domain models,
// and stream parsing and ranking. Nothing here may depend on the Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    // :app targets Java 11, so this module must too.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // @Immutable / @Stable only; the full Compose runtime stays out of this module.
    api(libs.androidx.compose.runtime.annotation)
    api(libs.javax.inject)
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.retrofit.moshi)
    api(libs.moshi)
    ksp(libs.moshi.codegen)

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // `-Dfetch.updateStreamFixtures=true` rewrites the stream parser's fixture table
    // (StreamTitleParserFixturesTest).
    System.getProperty("fetch.updateStreamFixtures")?.let { systemProperty("fetch.updateStreamFixtures", it) }
}
