package com.mousike.app
import android.app.Application
import com.mousike.app.playback.VisitorSession
import coil.ImageLoader
import coil.ImageLoaderFactory
class MousikeApplication: Application(),ImageLoaderFactory {
    override fun onCreate(){super.onCreate();VisitorSession.initialize(this);com.mousike.app.playback.PythonEngine.install(this);com.mousike.app.performance.CacheMaintenance.start(this);android.os.Handler(mainLooper).post {com.mousike.app.session.AndroidSessionQueue.get(this)}}
    override fun onTrimMemory(level: Int) {super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)com.mousike.app.performance.CacheMaintenance.trimMemory(this)}
    override fun newImageLoader(): ImageLoader {
        val dispatcher=okhttp3.Dispatcher().apply {maxRequests=4;maxRequestsPerHost=2}
        val http=okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectTimeout(5,java.util.concurrent.TimeUnit.SECONDS).readTimeout(7,java.util.concurrent.TimeUnit.SECONDS).build()
        return ImageLoader.Builder(this).okHttpClient(http).crossfade(false).diskCache {coil.disk.DiskCache.Builder().directory(java.io.File(cacheDir,"artwork-v2")).maxSizeBytes(48L*1024*1024).build()}.memoryCache {coil.memory.MemoryCache.Builder(this).maxSizeBytes(minOf(24L*1024*1024,(Runtime.getRuntime().maxMemory()*.08).toLong()).toInt()).build()}.build()
    }
}
