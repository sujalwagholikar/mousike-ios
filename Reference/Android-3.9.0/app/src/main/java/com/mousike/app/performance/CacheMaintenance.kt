package com.mousike.app.performance

import android.content.Context
import coil.imageLoader
import com.mousike.app.playback.AudioCache
import com.mousike.app.playback.InnerTubeExtractor
import com.mousike.app.playback.PythonEngine
import kotlinx.coroutines.*
import java.io.File

/** Only disposable caches: never removes saved music, likes, playlists, settings or runtime files. */
object CacheMaintenance {
    private val trimming=java.util.concurrent.atomic.AtomicBoolean()
    private val io=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    fun start(context: Context) {io.launch {
        val c=context.applicationContext
        listOf(File(c.cacheDir,"image_cache"),File(c.cacheDir,"audio-v1"),File(c.cacheDir,"yt-dlp-cache"),File(c.cacheDir,"metadata-v1"),
            File(c.filesDir,"youtubedl-android"),File(c.noBackupFilesDir,"youtubedl-android"))
            .forEach {runCatching {it.deleteRecursively()}}
        // Initialize/trim the media index away from Main before the first selected song.
        runCatching {AudioCache.get(c)}
    }}
    fun trimMemory(context: Context) {if(!trimming.compareAndSet(false,true))return;io.launch {try {
        context.imageLoader.memoryCache?.clear()
        InnerTubeExtractor.get(context).trimTemporary()
        runCatching {PythonEngine.call("trim",timeoutMs=2500)}
    } finally {trimming.set(false)}}}
    suspend fun clearTemporary(context: Context) = withContext(Dispatchers.IO) {
        context.imageLoader.memoryCache?.clear()
        context.imageLoader.diskCache?.clear()
        AudioCache.clearTemporary()
        InnerTubeExtractor.get(context).trimTemporary()
        runCatching {PythonEngine.call("trim",timeoutMs=2500)}
    }
}
