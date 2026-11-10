package com.diptych.app.shell

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import java.io.IOException

enum class ArtKind(val file: String) {
    Cover("library_600x900.jpg"),
    Hero("library_hero.jpg"),
    Logo("logo.png"),
}

/** Cloudflare first, then the akamai mirror. These are the only hosts the app fetches art from. */
fun artUrls(appId: Int, kind: ArtKind): List<String> = listOf(
    "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/${kind.file}",
    "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$appId/${kind.file}",
)

private const val DISK_CACHE_BYTES = 50L * 1024 * 1024

/** App-wide loader with a 50 MB disk cache; Coil handles the caching. */
object ArtLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.applicationContext.cacheDir.resolve("steam_art"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(false)
            .build()
            .also { instance = it }
    }
}

/** A loader that fails every request without touching the network (screenshots, tests). */
fun offlineImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context.applicationContext)
        .components {
            add(Interceptor { chain ->
                ErrorResult(drawable = null, request = chain.request, throwable = IOException("offline")) as ImageResult
            })
        }
        .build()
