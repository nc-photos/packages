package io.flutter.plugins.videoplayer

import android.content.Context
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import java.io.File

// See: https://github.com/flutter/flutter/issues/28094
class CacheDataSourceFactory(
	private val context: Context,
	private val maxCacheSize: Long,
	private val maxFileSize: Long,
) : DataSource.Factory {
	override fun createDataSource(): DataSource {
		val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSize)
		val dbProvider = StandaloneDatabaseProvider(context)

		if (downloadCache == null) {
			downloadCache = SimpleCache(
				File(context.cacheDir, "video"), evictor, dbProvider
			)
		}

		return CacheDataSource(
			downloadCache!!,
			httpDataSourceFactory.createDataSource(),
			FileDataSource(),
			CacheDataSink(downloadCache!!, maxFileSize),
			CacheDataSource.FLAG_BLOCK_ON_CACHE or
					CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
			null
		)
	}

	val httpDataSourceFactory: DefaultHttpDataSource.Factory

	init {
		val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
		httpDataSourceFactory = DefaultHttpDataSource.Factory()
			.setUserAgent("nc-photos")
			.setAllowCrossProtocolRedirects(true)
			.setTransferListener(bandwidthMeter)
	}

	companion object {
		private var downloadCache: SimpleCache? = null
	}
}
