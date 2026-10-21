package com.fetch.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
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
import com.fetch.tv.core.runtime.PluginRuntimeHooks
import com.fetch.tv.core.sync.StartupSyncService
import com.fetch.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.fetch.tv.core.network.IPv4FirstDns
import com.fetch.tv.core.network.NoDelaySocketFactory
import com.fetch.tv.data.local.ImagePerformancePreferences
import com.fetch.tv.core.streams.ranking.AndroidDeviceDecodeCapabilities
import com.fetch.tv.core.streams.ranking.DeviceDecodeCapabilities
import com.fetch.tv.data.mediaserver.ServerMatcher
import com.fetch.tv.data.mediaserver.ServerUserStateProjection
import com.fetch.tv.core.debrid.DebridRulesMigration
import com.fetch.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FetchApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var androidTvChannelSyncService: dagger.Lazy<AndroidTvChannelSyncService>
    @Inject lateinit var deviceDecodeCapabilities: AndroidDeviceDecodeCapabilities
    @Inject lateinit var imagePerformancePreferences: ImagePerformancePreferences
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var serverUserStateProjection: dagger.Lazy<ServerUserStateProjection>
    @Inject lateinit var debridRulesMigration: dagger.Lazy<DebridRulesMigration>
    @Inject lateinit var serverMatcher: dagger.Lazy<ServerMatcher>

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PluginRuntimeHooks.onApplicationCreate(this)
        // None of these are needed for the first frame: the launcher channel only matters once
        // the app is backgrounded, and server matching/projection only react to later screens.
        // Building their dependency graphs (DataStores, Room, JobScheduler IPC) on the main
        // thread before the first frame is measurable on Fire TV Stick class devices, so start
        // them once the main looper first goes idle.
        Looper.myQueue().addIdleHandler {
            androidTvChannelSyncService.get().start()
            serverUserStateProjection.get().start()
            debridRulesMigration.get().start()
            serverMatcher.get().start()
            false
        }
        // Probe decoders/HDR/passthrough off the main thread so cold start isn't delayed;
        // stream ranking assumes a capable device until the probe lands.
        Thread({ DeviceDecodeCapabilities.current = deviceDecodeCapabilities.get() }, "decode-caps-probe")
            .apply { priority = Thread.MIN_PRIORITY }
            .start()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder()
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
                // - allowHardware(false) keeps bitmaps on heap instead of GPU memory
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
            .allowHardware(false)
            .allowRgb565(imagePerformancePreferences.rgb565Enabled)
            .bitmapFactoryMaxParallelism(4)
            .build()
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
