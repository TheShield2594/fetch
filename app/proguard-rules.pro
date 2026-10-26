# Add project specific ProGuard rules here.

# ── Moshi ──────────────────────────────────────────────────────────────────────
# Keep Moshi-generated JsonAdapter classes
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Keep @JsonClass-annotated classes and their generated adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# ── Gson ───────────────────────────────────────────────────────────────────────
# Keep TypeToken generic signatures (used in AddonConfigServer/RepositoryConfigServer)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────────────────
# Keep generic signatures for Retrofit service methods
-keepattributes Signature
# Keep Retrofit service interfaces (must preserve generic return types)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# Suspend service methods: Retrofit reads the result type from the Continuation's
# generic signature.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all project API interfaces
-keep class com.fetch.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Data classes (DTOs) ────────────────────────────────────────────────────────
# Keep all DTO classes used with Moshi/Retrofit
-keep class com.fetch.tv.data.remote.dto.** { *; }
-keep class com.fetch.tv.domain.model.** { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin Metadata for reflection
-keepattributes RuntimeVisibleAnnotations

# ── NanoHTTPD (used by local server) ───────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
# Keep server classes and their inner data classes (serialized with Gson)
-keep class com.fetch.tv.core.server.** { *; }

# ── Torrent streaming (Fetch Engine) ───────────────────────────────────────────
-keep class com.fetch.tv.core.torrent.** { *; }

# ── ExoPlayer / Media3 ────────────────────────────────────────────────────────
# Media3 ships consumer rules for its own reflection (extension renderers, media source
# and downloader factories). These cover what Fetch and the bundled decoders reach by name.
-dontwarn androidx.media3.**

# FFmpeg decoder (local module or AAR, neither ships rules): JNI_OnLoad finds
# FfmpegAudioDecoder.growOutputBuffer by name, and the native code fills
# SimpleDecoderOutputBuffer. The AV1, IAMF and MPEG-H AARs bring their own rules.
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keepnames class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
-keepclassmembers class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Private fields read by reflection: FetchAssMatroskaExtractor reads them on the vendored
# extractor, ass-media on Media3's.
-keepclassmembers class com.fetch.tv.core.player.dvmkv.MatroskaExtractor {
    androidx.media3.extractor.ExtractorOutput extractorOutput;
    *** subtitleSample;
}
-keepclassmembers class androidx.media3.extractor.mkv.MatroskaExtractor {
    androidx.media3.extractor.ExtractorOutput extractorOutput;
    *** subtitleSample;
}

# DolbyVisionExtractorsFactory tells extractors apart by class name.
-keepnames class * implements androidx.media3.extractor.Extractor

# Called by reflection when the running Media3 has it (PlayerScreen).
-keepclassmembers class androidx.media3.ui.PlayerView {
    public void setEnableComposeSurfaceSyncWorkaround(boolean);
}

# Playback diagnostics and issue reports name the exception class.
-keepnames class androidx.media3.** extends java.lang.Throwable

# ── Supabase / Ktor / Kotlinx Serialization ───────────────────────────────────
# Ktor ships rules for its ServiceLoader engines and AtomicFU fields; Supabase talks to
# its API through kotlinx.serialization, whose generated serializers are kept by the
# library's own rules and the rule below.
-dontwarn io.ktor.**
-keep class com.fetch.tv.data.remote.supabase.** { *; }
# Keep @Serializable classes and their generated serializers
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── General ────────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MPV (native JNI callbacks)
# Native code reflects into multiple classes/methods under is.xyz.mpv,
# so keep the whole package to avoid JNI lookup crashes after R8.
-keep class is.xyz.mpv.** { *; }

# ── Missing class stubs ───────────────────────────────────────────────────────
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn okhttp3.internal.sse.**

-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient