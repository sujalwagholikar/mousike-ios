package com.mousike.app.playback

import android.content.Context
import com.mousike.app.settings.AppSettings
import com.mousike.app.performance.SingleFlightCache
import com.mousike.app.performance.ResolutionSignal
import com.mousike.app.performance.BoundedResolution
import com.mousike.app.performance.ForegroundResolver
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class InnerTubeExtractor private constructor(private val context: Context) {
    data class Stream(val url: String,val headers: Map<String,String>,val expiresAt: Long,val isHls: Boolean=false,val native: Boolean=false,val format: String="")
    private val freshIds=ConcurrentHashMap<String,Boolean>()
    private val streams=ConcurrentHashMap<String,Stream>()
    private val native=NativeAudioResolver()
    private val background=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    private val connections=com.mousike.app.performance.ConnectionWork<String,NativeAudioResolver.Result>()
    private val verifier=StreamVerifier()
    private val publicationEpoch=java.util.concurrent.atomic.AtomicLong()
    private val foreground=java.util.concurrent.atomic.AtomicInteger()
    private val alternatives=java.util.concurrent.ConcurrentHashMap<String,List<Stream>>()
    private val checkedAt=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val rejected=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun cacheNative(id: String,key: String,result: NativeAudioResolver.Result) {
        if(streams.size>=32)streams.keys.firstOrNull {it!=key}?.let {streams.remove(it);alternatives.remove(it)}
        alternatives[key]=(result.candidates+listOfNotNull(result.alternate)).filter {it.url!=result.stream.url}.distinctBy {it.url}
        if(checkedAt.size>=128)checkedAt.clear()
        streams[key]=result.stream;checkedAt[result.stream.url]=System.currentTimeMillis();remember(id,result.metadata)
    }
    private fun nativeConnection(id: String,quality: String,signal: ResolutionSignal): NativeAudioResolver.Result {
        val result=native.resolve(id,quality,signal,fresh=freshIds.remove(id)==true) ?: throw IOException("No native audio candidates")
        val candidates=(result.candidates+listOfNotNull(result.stream,result.alternate)).distinctBy {it.url}
            .filter {candidate ->candidate.expiresAt>System.currentTimeMillis()+15000 && !isRejected(candidate.url)}
        if(candidates.isEmpty())throw IOException("No usable audio candidates")
        val selected=verifier.select(candidates,signal){reject(it.url)}
        return result.copy(stream=selected,candidates=candidates.filter {!isRejected(it.url)},alternate=candidates.firstOrNull {it.url!=selected.url && !isRejected(it.url)})
    }
    private fun cancelWarmup(){connections.cancelSpeculationExcept(null)}
    private val nativeFailed=ConcurrentHashMap<String,Long>()
    private fun nativeTemporarilyFailed(id: String)=nativeFailed[id]?.let {System.currentTimeMillis()-it<60000} ?: false
    private val infoWork=SingleFlightCache<String,JSONObject>(32)
    private val metadata=ConcurrentHashMap<String,JSONObject>()
    @Volatile private var initialized=false
    @Volatile var initializationError: String?=null; private set
    @Synchronized fun initialize() {
        if(initialized)return
        try {PythonEngine.call("warmup");initialized=true;initializationError=null}
        catch(e: Exception){initializationError="Python audio runtime could not start: ${e.message}";throw e}
    }
    private fun remember(id: String,o: JSONObject): JSONObject {
        metadata[id]=o
        if(metadata.size>32)metadata.keys.firstOrNull { it!=id }?.let { metadata.remove(it) }
        return o
    }
    suspend fun info(id: String): JSONObject=withContext(Dispatchers.IO) { infoBlocking(id) }
    fun infoBlocking(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        metadata[id]?.let {return it}
        return infoWork.get(id) {metadata[id] ?: remember(id,PythonEngine.call("metadata",JSONObject().put("id",id),timeoutMs=9000))}
    }
    fun resolve(id: String,force: Boolean=false,quality: String=AppSettings.get(context).state.value.audioQuality,
        signal: ResolutionSignal=ResolutionSignal()): Stream {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) {"Invalid YouTube video ID"}
        signal.check()
        val key=AudioCache.key(id,quality)
        connections.cancelSpeculationExcept(key)
        if(force){streams.remove(key);freshIds[id]=true}
        streams[key]?.takeIf {it.expiresAt>System.currentTimeMillis()+60000}?.let {cached ->
            if((checkedAt[cached.url] ?: 0)>System.currentTimeMillis()-120000)return cached
            try {verifier.verify(cached,signal);checkedAt[cached.url]=System.currentTimeMillis();return cached}
            catch(e: Exception){signal.check();streams.remove(key,cached);reject(cached.url)}
        }
        publicationEpoch.incrementAndGet();foreground.incrementAndGet()
        try {return ForegroundResolver.resolve(signal,delayMs=if(nativeTemporarilyFailed(id))0 else 900,budgetMs=16000,
            primary=primary@{request ->
                if(nativeTemporarilyFailed(id))null else {
                    val options=alternatives[key].orEmpty().filter {!isRejected(it.url)}
                    if(options.isNotEmpty()) {
                        runCatching {verifier.select(options,request){reject(it.url)}}.getOrNull()?.let {chosen ->
                            return@primary NativeAudioResolver.Result(chosen,metadata[id] ?: JSONObject(),candidates=options)
                        }
                    }
                    connections.get(key,request,18000) {nativeConnection(id,quality,it)}
                }
            },fallback={extraction ->
                PlaybackDiagnostics.stage(id,"Trying another connection")
                val result=native.resolve(id,quality,extraction,fallback=true) ?: throw IOException("No compatible audio candidates")
                val candidates=result.candidates.ifEmpty {listOf(result.stream)}
                val selected=verifier.select(candidates,extraction){reject(it.url)}
                result.copy(stream=selected)
            }).let {result ->
                signal.check();cacheNative(id,key,result);result.stream
            }} finally {foreground.decrementAndGet()}
    }
    /** Warm only one validated connection; a tap joins it instead of cancelling/restarting it. */
    @Synchronized fun prefetch(tracks: List<com.mousike.app.data.model.Track>) {
        if(!AppSettings.get(context).state.value.preloadNext || com.mousike.app.RuntimeMode.offlineFixture || foreground.get()>0)return
        val track=tracks.firstOrNull {it.streamUrl.isBlank() && it.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) && !nativeTemporarilyFailed(it.videoId)} ?: return
        val quality=AppSettings.get(context).state.value.audioQuality;val key=AudioCache.key(track.videoId,quality)
        if(hasCachedStream(track.videoId,quality))return
        val epoch=publicationEpoch.get()
        connections.prepare(key,{signal ->Thread.sleep(500);nativeConnection(track.videoId,quality,signal)},{result ->if(publicationEpoch.get()==epoch)cacheNative(track.videoId,key,result)})
    }
    fun useAlternate(id: String): Boolean {
        val key=AudioCache.key(id,AppSettings.get(context).state.value.audioQuality)
        streams.remove(key)?.let {reject(it.url)}
        val options=alternatives[key].orEmpty().filter {it.expiresAt>System.currentTimeMillis()+60000 && !isRejected(it.url)}
        // Health-check alternates on the connection worker, never the UI thread.
        if(options.isEmpty())return false
        alternatives[key]=options.drop(1);streams[key]=options.first();checkedAt.remove(options.first().url)
        PlaybackDiagnostics.stage(id,"Connecting");return true
    }
    private fun isRejected(url: String): Boolean {
        val at=rejected[url] ?: return false
        if(System.currentTimeMillis()-at>=60000){rejected.remove(url,at);return false};return true
    }
    private fun reject(url: String) {
        if(rejected.size>=128)rejected.clear()
        rejected[url]=System.currentTimeMillis();checkedAt.remove(url)
    }
    fun retryFresh(id: String) {
        freshIds[id]=true
        val quality=AppSettings.get(context).state.value.audioQuality
        val key=AudioCache.key(id,quality)
        streams.remove(key)?.let {reject(it.url)};alternatives.remove(key);connections.discard(key);nativeFailed.remove(id)
    }
    fun hasCachedStream(id: String,quality: String)=streams[AudioCache.key(id,quality)]?.expiresAt?.let {it>System.currentTimeMillis()+60000} ?: false
    fun hasNativeStream(id: String)=streams.entries.any {it.key.endsWith(":$id") && it.value.native}
    fun useFallback(id: String) {cancelWarmup();if(nativeFailed.size>=128)nativeFailed.clear();nativeFailed[id]=System.currentTimeMillis();invalidate(id)}
    fun invalidate(id: String) { listOf("Data saver","High","Normal",AppSettings.get(context).state.value.audioQuality).forEach { streams.remove(AudioCache.key(id,it));alternatives.remove(AudioCache.key(id,it)) } }
    fun trimTemporary() {freshIds.clear();metadata.clear();infoWork.clear();checkedAt.clear();rejected.clear();nativeFailed.clear()}
    suspend fun update(): String=withContext(Dispatchers.IO) {
        PythonEngine.call("reset");VisitorSession.clear();metadata.clear();infoWork.clear();streams.clear();alternatives.clear()
        "Python InnerTube session refreshed. Runtime updates ship with the app."
    }
    companion object {
        @Volatile private var instance: InnerTubeExtractor?=null
        fun get(context: Context)=instance ?: synchronized(this) {instance ?: InnerTubeExtractor(context.applicationContext).also {instance=it}}
    }
}
