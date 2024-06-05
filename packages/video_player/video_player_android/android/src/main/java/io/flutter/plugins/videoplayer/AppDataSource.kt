package io.flutter.plugins.videoplayer

import android.content.Context
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import io.flutter.plugins.videoplayer.Messages.MessageLivePhotoType
import java.io.File
import java.util.Queue
import kotlin.math.max
import kotlin.math.min

class AppDataSourceFactory(
	private val context: Context,
	private val maxCacheSize: Long,
	private val maxFileSize: Long,
	private val livePhotoType: MessageLivePhotoType?,
) : DataSource.Factory {
	override fun createDataSource(): DataSource {
		val evictor = LeastRecentlyUsedCacheEvictor(maxCacheSize)
		val dbProvider = StandaloneDatabaseProvider(context)

		if (downloadCache == null) {
			downloadCache = SimpleCache(
				File(context.cacheDir, "video"), evictor, dbProvider
			)
		}

		return AppDataSource(
			upstreamDataSrc=CacheDataSource(
				downloadCache!!,
				httpDataSourceFactory.createDataSource(),
				FileDataSource(),
				CacheDataSink(downloadCache!!, maxFileSize),
				CacheDataSource.FLAG_BLOCK_ON_CACHE or
						CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
				null
			),
			livePhotoType=livePhotoType,
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

class AppDataSource(
	private val upstreamDataSrc: DataSource,
	private val livePhotoType: MessageLivePhotoType?,
) : DataSource {
	companion object {
		// ftyp
		private val mp4Signature = listOf<Byte>(0x66, 0x74, 0x79, 0x70)
		private const val headerSize = 8
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (length == 0) {
			return 0
		}
		if (livePhotoType != null) {
			if (extraBytes != null && extraBytes!!.isNotEmpty()) {
				// return extra bytes first
				val count = min(extraBytes!!.size, length)
				extraBytes!!.copyInto(buffer, offset, 0, count)
				extraBytes = extraBytes!!.sliceArray(count until extraBytes!!.size)
				return count
			}
			if (hasHeader) {
				// found header and no extra bytes, just pass through the call
				return upstreamDataSrc.read(buffer, offset, length)
			}
			// check header
			val readLength = 128
			val buf = ByteArray(readLength + headerSize)
			var index = 0
			// skip first header as HEIC uses the MP4 container
			while (true) {
				val count = upstreamDataSrc.read(buf, 0, headerSize)
				val dataSize = index + count
				if (count <= 0) {
					return count
				} else if (dataSize >= headerSize) {
					buf.sliceArray(headerSize until dataSize).copyInto(buf)
					index = dataSize - headerSize
					break
				} else {
					index += count
				}
			}
			headerPosition += headerSize
			while (true) {
				val count = upstreamDataSrc.read(buf, index, readLength)
				if (count > 0) {
					val signatureIndex = indexOfSignature(buf, index + count)
					if (signatureIndex == null) {
						// no mp4 signature yet
						val dataSize = index + count
						// keep some data at the end in case it contains
						// part of the header
						val keep = max(dataSize - headerSize, 0) until dataSize
						buf.sliceArray(keep).copyInto(buf)
						index = min(dataSize, headerSize)
						// read again
						headerPosition += count
					} else {
						headerPosition = headerPosition - index + signatureIndex
						Log.i("AppDataSource", "MP header found at 0x%X".format(headerPosition))
						hasHeader = true
						val dataCount = count + index - signatureIndex
						if (dataCount > length) {
							extraBytes = buf.sliceArray(signatureIndex + length until signatureIndex + dataCount)
							buf.copyInto(buffer, offset, signatureIndex, signatureIndex + length)
							return length
						} else {
							buf.copyInto(buffer, offset, signatureIndex, count + index)
							return count + index - signatureIndex
						}
					}
				} else {
					return count
				}
			}
		} else {
			return upstreamDataSrc.read(buffer, offset, length)
		}
	}

	override fun addTransferListener(transferListener: TransferListener) =
		upstreamDataSrc.addTransferListener(transferListener)

	override fun open(dataSpec: DataSpec): Long {
		val builder = DataSpec.Builder()
			.setUri(dataSpec.uri)
			.setUriPositionOffset(dataSpec.uriPositionOffset)
			.setHttpMethod(dataSpec.httpMethod)
			.setHttpBody(dataSpec.httpBody)
			.setHttpRequestHeaders(dataSpec.httpRequestHeaders)
			.setPosition(dataSpec.position)
			.setLength(dataSpec.length)
			.setKey(dataSpec.key)
			.setFlags(dataSpec.flags)
			.setCustomData(dataSpec.customData)
		if (livePhotoType != null) {
			if (hasHeader) {
				builder.setPosition(dataSpec.position + headerPosition)
			}
		}

		val spec = builder.build()
		val raw = upstreamDataSrc.open(spec)
		return if (livePhotoType != null) {
			if (raw == C.LENGTH_UNSET.toLong()) {
				raw
			} else if (hasHeader) {
				raw - headerPosition
			} else {
				C.LENGTH_UNSET.toLong()
			}
		} else {
			raw
		}
	}

	override fun getUri() = upstreamDataSrc.uri

	override fun close() {
		upstreamDataSrc.close()
		extraBytes = null
	}

	private fun indexOfSignature(buf: ByteArray, length: Int): Int? {
		val check = ArrayDeque<Byte>(4)
		var i = 0
		val result = buf.indexOfFirst {
			if (i++ >= length) {
				return null
			}
			while (check.size >= 4) {
				check.removeFirst()
			}
			check.addLast(it)
			return@indexOfFirst if (check.size < 4) {
				false
			} else {
				check == mp4Signature
			}
		}
		// result would be the index of the last character of the signature, so
		// we need to subtract (signature length - 1) from it
		// mp4 header starts at 4 bytes before ftyp chunk
		return if (result == -1) null else result - (mp4Signature.size - 1) - 4
	}

	private var hasHeader = false
	private var headerPosition = 0
	// as we need to check for the header, we may have more bytes on hand than
	// the buffer length, so we store it first then return them in future read
	private var extraBytes: ByteArray? = null
}
