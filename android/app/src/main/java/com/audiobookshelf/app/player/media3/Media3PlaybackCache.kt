package com.audiobookshelf.app.player.media3

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.device.DeviceManager
import java.io.File
import java.io.IOException

/** Shared bounded cache for direct-play Media3 HTTP audio requests. */
@UnstableApi
object Media3PlaybackCache {
  private const val TAG = "Media3PlaybackCache"
  private const val CACHE_DIR_NAME = "media3-playback-cache"
  private const val CACHE_HIT_LOG_BYTES = 8L * 1024L * 1024L
  private const val CACHE_HIT_LOG_INTERVAL_MS = 30_000L

  /**
   * User-configured cache limit in bytes, 0 when disabled. The LRU evictor captures this at cache
   * creation, so a settings change applies on the next app launch.
   */
  fun configuredCacheSizeBytes(): Long {
    // Null only before DeviceManager backfills settings; match its 256MB default
    val sizeMB = DeviceManager.deviceData.deviceSettings?.streamingCacheSizeMB ?: 256
    return sizeMB.coerceAtLeast(0).toLong() * 1024L * 1024L
  }

  @Volatile
  private var cache: SimpleCache? = null
  val eventListener = LoggingCacheEventListener()

  fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
    cache ?: createCache(context.applicationContext).also { cache = it }
  }

  private fun createCache(context: Context): SimpleCache {
    val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    val maxBytes = configuredCacheSizeBytes()
    val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
    val databaseProvider = StandaloneDatabaseProvider(context)
    Log.i(TAG, "Initializing playback cache dir=${cacheDir.absolutePath} maxBytes=$maxBytes")
    return SimpleCache(cacheDir, evictor, databaseProvider)
  }

  @UnstableApi
  class LoggingCacheEventListener : CacheDataSource.EventListener {
    private var cachedBytesSinceLastLog = 0L
    private var lastHitLogMs = 0L

    @Synchronized
    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
      cachedBytesSinceLastLog += cachedBytesRead
      val now = System.currentTimeMillis()
      if (cachedBytesSinceLastLog < CACHE_HIT_LOG_BYTES && now - lastHitLogMs < CACHE_HIT_LOG_INTERVAL_MS) {
        return
      }
      Log.i(
        TAG,
        "cacheHit cachedBytesRead=$cachedBytesSinceLastLog cacheSizeBytes=$cacheSizeBytes"
      )
      cachedBytesSinceLastLog = 0L
      lastHitLogMs = now
    }

    override fun onCacheIgnored(reason: Int) {
      Log.w(TAG, "cacheIgnored reason=${cacheIgnoredReasonLabel(reason)}")
    }

    private fun cacheIgnoredReasonLabel(reason: Int): String = when (reason) {
      CacheDataSource.CACHE_IGNORED_REASON_ERROR -> "ERROR"
      CacheDataSource.CACHE_IGNORED_REASON_UNSET_LENGTH -> "UNSET_LENGTH"
      else -> reason.toString()
    }
  }
}

/**
 * Routes cacheable direct-play server audio through CacheDataSource and everything else directly
 * through the existing upstream factory.
 */
@UnstableApi
class Media3CacheRoutingDataSourceFactory(
  private val upstreamFactory: DataSource.Factory,
  private val cacheDataSourceFactory: DataSource.Factory,
  private val isCacheable: (Uri) -> Boolean
) : DataSource.Factory {
  override fun createDataSource(): DataSource = Media3CacheRoutingDataSource(
    upstreamFactory.createDataSource(),
    cacheDataSourceFactory.createDataSource(),
    isCacheable
  )
}

@UnstableApi
private class Media3CacheRoutingDataSource(
  private val upstreamDataSource: DataSource,
  private val cacheDataSource: DataSource,
  private val isCacheable: (Uri) -> Boolean
) : DataSource {
  private var activeDataSource: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    upstreamDataSource.addTransferListener(transferListener)
    cacheDataSource.addTransferListener(transferListener)
  }

  @Throws(IOException::class)
  override fun open(dataSpec: DataSpec): Long {
    val useCache = isCacheable(dataSpec.uri)
    activeDataSource = if (useCache) cacheDataSource else upstreamDataSource
    if (useCache && BuildConfig.DEBUG) {
      Log.d("Media3PlaybackCache", "cache enabled uri=${dataSpec.uri} key=${dataSpec.key}")
    }
    return activeDataSource!!.open(dataSpec)
  }

  @Throws(IOException::class)
  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    return activeDataSource!!.read(buffer, offset, length)
  }

  override fun getUri(): Uri? = activeDataSource?.uri

  override fun getResponseHeaders(): Map<String, List<String>> =
    activeDataSource?.responseHeaders ?: emptyMap()

  @Throws(IOException::class)
  override fun close() {
    try {
      activeDataSource?.close()
    } finally {
      activeDataSource = null
    }
  }
}

fun isCacheableMedia3PlaybackUri(uri: Uri): Boolean {
  val scheme = uri.scheme ?: return false
  if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
    return false
  }

  val serverHost = DeviceManager.serverAddress.toUri().host ?: return false
  if (!uri.host.equals(serverHost, ignoreCase = true)) return false

  val path = uri.path ?: return false
  return path.startsWith("/public/session/") && path.contains("/track/")
}

@UnstableApi
fun buildMedia3PlaybackCacheDataSourceFactory(
  context: Context,
  upstreamFactory: DataSource.Factory
): DataSource.Factory {
  if (Media3PlaybackCache.configuredCacheSizeBytes() <= 0L) {
    Log.i("Media3PlaybackCache", "Streaming cache disabled by device setting; using upstream only")
    return upstreamFactory
  }
  return runCatching {
    val cacheDataSourceFactory = CacheDataSource.Factory()
      .setCache(Media3PlaybackCache.get(context))
      .setUpstreamDataSourceFactory(upstreamFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      .setEventListener(Media3PlaybackCache.eventListener)

    Media3CacheRoutingDataSourceFactory(
      upstreamFactory = upstreamFactory,
      cacheDataSourceFactory = cacheDataSourceFactory,
      isCacheable = ::isCacheableMedia3PlaybackUri
    )
  }.getOrElse { throwable ->
    Log.w("Media3PlaybackCache", "Playback cache unavailable; using upstream only", throwable)
    upstreamFactory
  }
}