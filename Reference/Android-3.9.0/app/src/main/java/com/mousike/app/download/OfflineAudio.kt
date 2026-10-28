package com.mousike.app.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.source.MediaSource
import com.mousike.app.playback.AudioNetwork
import com.mousike.app.playback.InnerTubeExtractor
import org.json.JSONObject
import java.io.File

/** Downloaded HLS is permanent cache, never the evictable streaming cache. */
@androidx.annotation.OptIn(UnstableApi::class)
object OfflineAudio {
    private val locks=Array(16){Any()}
    private val cleanup=java.util.concurrent.Executors.newSingleThreadExecutor {r ->Thread(r,"offline-cleanup").apply {isDaemon=true}}
    fun <T> withSourceLock(key: String,block: ()->T): T=synchronized(locks[(key.hashCode() and Int.MAX_VALUE)%locks.size]) {block()}
    fun remove(context: Context,marker: File,otherMarkers: List<File>) {
        if(!marker.name.endsWith(".mousike-hls.json"))return
        val url=runCatching {JSONObject(marker.readText()).getString("url")}.getOrNull() ?: return
        if(otherMarkers.any {runCatching {JSONObject(it.readText()).optString("url")==url}.getOrDefault(false)})return
        cleanup.execute {runCatching {HlsDownloader(MediaItem.fromUri(url),CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(null)).remove()}}
    }
    fun clear(context: Context){cleanup.execute {val storage=cache(context);storage.keys.forEach {storage.removeResource(it)}}}
    @Volatile private var cache: SimpleCache?=null
    fun cache(context: Context)=cache ?: synchronized(this){cache ?: SimpleCache(File(context.filesDir,"offline_hls"),NoOpCacheEvictor(),StandaloneDatabaseProvider(context)).also {cache=it}}
    fun downloader(context: Context,stream: InnerTubeExtractor.Stream,quality: String,selectedKeys: MutableList<androidx.media3.common.StreamKey>): HlsDownloader {
        val data=CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(
            OkHttpDataSource.Factory(AudioNetwork.http).setDefaultRequestProperties(stream.headers))
        val playlist=AudioNetwork.http.newCall(okhttp3.Request.Builder().url(stream.url).apply {stream.headers.forEach {(k,v)->header(k,v)}}.build()).execute().use {response ->
            check(response.isSuccessful){"HLS playlist unavailable"}
            androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(Uri.parse(stream.url),checkNotNull(response.body).byteStream())
        }
        val keys=if(playlist is androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist) {
            val audio=playlist.audios.mapIndexedNotNull {i,r->if(r.url!=null)i to r else null}
            if(audio.isNotEmpty()) {
                val selected=com.mousike.app.settings.AudioQualityPolicy.select(audio,quality){it.second.format.bitrate.coerceAtLeast(0)}!!
                listOf(androidx.media3.common.StreamKey(1,selected.first))
            } else {
                val variants=playlist.variants.mapIndexedNotNull {i,v ->
                    val codecs=v.format.codecs.orEmpty().lowercase()
                    if(v.format.height<=0 && codecs.isNotBlank() && codecs.split(',').all {c->listOf("mp4a","opus","vorbis","ac-3","ec-3","flac").any {c.trim().startsWith(it)}})i to v else null}
                val selected=com.mousike.app.settings.AudioQualityPolicy.select(variants,quality){it.second.format.bitrate.coerceAtLeast(0)}
                    ?: error("No audio-only HLS download rendition")
                listOf(androidx.media3.common.StreamKey(0,selected.first))
            }
        } else emptyList()
        selectedKeys.addAll(keys)
        return HlsDownloader(MediaItem.Builder().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(keys).build(),data)
    }
    fun source(context: Context,item: MediaItem): MediaSource {
        val marker=File(checkNotNull(item.localConfiguration?.uri?.path))
        val root=JSONObject(marker.readText())
        val url=root.getString("url")
        val encoded=root.optJSONArray("keys")
        val keys=if(encoded==null)emptyList() else (0 until encoded.length()).map {i ->val pair=encoded.getJSONArray(i);androidx.media3.common.StreamKey(pair.getInt(0),pair.getInt(1))}
        val local=CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(null)
        return HlsMediaSource.Factory(local).createMediaSource(item.buildUpon().setUri(Uri.parse(url)).setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(keys).build())
    }
}
