package com.fetch.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Looper
import android.os.StatFs
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.fetch.tv.core.image.BrowserImageRequestHeadersInterceptor
import com.fetch.tv.core.image.CustomPosterFallbackInterceptor
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.fetch.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.fetch.tv.core.util.AppLocaleResources
import com.fetch.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.core.network.SharedHttpClient
import com.fetch.tv.data.local.ImagePerformancePreferences
import com.fetch.tv.core.streams.ranking.AndroidDeviceDecodeCapabilities
import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.data.mediaserver.ServerUserStateProjection
import com.fetch.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FetchApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var androidTvChannelSyncService: dagger.Lazy<AndroidTvChannelSyncService>
    // All lazy: field injection runs inside onCreate, before the first frame, so an eager field
    // builds its whole graph there (the image preferences read SharedPreferences from disk, the
    // Simkl holder builds the Trakt settings DataStore).
    @Inject lateinit var deviceDecodeCapabilities: dagger.Lazy<AndroidDeviceDecodeCapabilities>
    @Inject lateinit var imagePerformancePreferences: dagger.Lazy<ImagePerformancePreferences>
    @Inject lateinit var simklAnimeIdPreferenceHolder: dagger.Lazy<SimklAnimeIdPreferenceHolder>
    @Inject lateinit var serverUserStateProjection: dagger.Lazy<ServerUserStateProjection>

    override fun onCreate() {
        // Before super.onCreate(), so disk access during Hilt injection is reported too.
        enableStrictModeInDebug()
        super.onCreate()
        // None of these are needed for the first frame: the launcher channel only matters once
        // the app is backgrounded, and server projection only reacts to later screens. Building their dependency graphs (DataStores,
        // sync services, JobScheduler IPC) on the main thread before the first frame is
        // measurable on Fire TV Stick class devices, so build them once the main looper first
        // goes idle. They are injected lazily so field injection doesn't build them up front.
        Looper.myQueue().addIdleHandler {
            androidTvChannelSyncService.get().start()
            serverUserStateProjection.get().start()
            // Starts following the Simkl anime-id setting; projections use the default until then.
            simklAnimeIdPreferenceHolder.get()
            false
        }
        // Remove sign-in reports older builds queued on disk; sign-in diagnostics are gone.
        Thread({ runCatching { java.io.File(filesDir, "auth_diagnostics_queue.jsonl").delete() } }, "auth-report-cleanup")
            .apply { priority = Thread.MIN_PRIORITY }
            .start()
        // Probe decoders/HDR/passthrough off the main thread so cold start isn't delayed;
        // stream ranking assumes a capable device until the probe lands.
        Thread({ DeviceDecodeCapabilities.current = deviceDecodeCapabilities.get().get() }, "decode-caps-probe")
            .apply { priority = Thread.MIN_PRIORITY }
            .start()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    /** Gives back trailer player and cache memory when the system asks (see [MemoryTrim]). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.fetch.tv.core.memory.MemoryTrim.onTrimMemory(level)
    }

    /**
     * Resources in the app's own language (#324). The language is applied to the activity in
     * MainActivity.attachBaseContext, but ViewModels, repositories and services resolve their
     * messages through an injected `@ApplicationContext`, which is this Application: without
     * this it kept the device's language, so a German app on an English TV showed English
     * errors and statuses. Follows a language change at once (LocaleCache is read on every
     * call) and a system configuration change (the base configuration is part of the cache key).
     */
    override fun getResources(): Resources {
        val base = super.getResources()
        // Before attachBaseContext there is nothing to derive a configuration context from.
        if (baseContext == null) return base
        return appLocaleResources.resolve(base, LocaleCache.localeTag)
    }

    private val appLocaleResources = AppLocaleResources { configuration ->
        createConfigurationContext(configuration)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val imagePrefs = imagePerformancePreferences.get()
        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            SharedHttpClient.root.newBuilder()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .socketFactory(NoDelaySocketFactory)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(BrowserImageRequestHeadersInterceptor())
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                add(CustomPosterFallbackInterceptor())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                            )
                        },
                    )
                )
            }
            .memoryCache {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.15 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.20 for decent image caching.
                // Normal devices (>3GB): use 0.25 for snappy image loading.
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build()
            }
            .diskCache {
                val directory = cacheDir.resolve("image_cache")
                DiskCache.Builder()
                    .directory(directory.toOkioPath())
                    .maxSizeBytes(imageDiskCacheSizeBytes(directory))
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            // Software bitmaps by default; hardware ones skip the per-draw GPU upload and free the
            // Java heap, but RGB565 then no longer applies. Settings → Advanced has the switch.
            .allowHardware(imagePrefs.hardwareBitmapsEnabled)
            .allowRgb565(imagePrefs.rgb565Enabled)
            .bitmapFactoryMaxParallelism(4)
            .build()
    }

    /**
     * Debug builds log (never crash on) disk reads and writes on the main thread, and leaked
     * closeables, to find main-thread file syscalls such as the ones the audit found in
     * ProfileDataStoreFactory (#454). Filter logcat by the StrictMode tag. Off in every build
     * that is measured or shipped.
     */
    private fun enableStrictModeInDebug() {
        if (BuildConfig.BUILD_TYPE != "debug") return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    /**
     * Fire TV Sticks often run with well under 1 GB free, so a flat 200 MB image cache can
     * starve the system and other apps. Size it to [IMAGE_DISK_CACHE_FREE_FRACTION] of the
     * space the cache could use (free space plus what it already occupies), clamped to
     * [IMAGE_DISK_CACHE_MIN_BYTES]..[IMAGE_DISK_CACHE_MAX_BYTES]. Coil builds the disk cache
     * lazily on a background thread, so the StatFs/listing cost stays off the main thread.
     */
    private fun imageDiskCacheSizeBytes(directory: java.io.File): Long {
        return try {
            directory.mkdirs()
            val freeBytes = StatFs(directory.absolutePath).availableBytes
            val usedBytes = directory.listFiles()?.sumOf { it.length() } ?: 0L
            ((freeBytes + usedBytes) * IMAGE_DISK_CACHE_FREE_FRACTION).toLong()
                .coerceIn(IMAGE_DISK_CACHE_MIN_BYTES, IMAGE_DISK_CACHE_MAX_BYTES)
        } catch (e: Exception) {
            IMAGE_DISK_CACHE_MIN_BYTES
        }
    }
}

private const val IMAGE_DISK_CACHE_FREE_FRACTION = 0.05
private const val IMAGE_DISK_CACHE_MIN_BYTES = 32L * 1024 * 1024
private const val IMAGE_DISK_CACHE_MAX_BYTES = 200L * 1024 * 1024
