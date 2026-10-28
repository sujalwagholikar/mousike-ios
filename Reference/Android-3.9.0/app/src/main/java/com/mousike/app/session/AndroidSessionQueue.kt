package com.mousike.app.session

import android.content.Context
import com.mousike.app.data.catalog.MusicCatalog
import com.mousike.app.data.model.*
import com.mousike.app.data.remote.YouTubeMusicClient
import com.mousike.app.playback.AudioPlayerEngine
import com.mousike.app.playback.InnerTubeExtractor
import com.mousike.app.recommendation.TasteRanker
import com.mousike.app.settings.AppSettings
import com.mousike.app.ui.personalization.TasteActivityReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*

/** Uses the unchanged engine's public queue operations and existing metadata client. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AndroidSessionQueue {
 @Volatile private var instance: SessionQueueController?=null
 fun get(context: Context): SessionQueueController=instance ?: synchronized(this){instance ?: create(context.applicationContext).also {instance=it}}
 @OptIn(FlowPreview::class)
 private fun create(context: Context): SessionQueueController {
  val engine=AudioPlayerEngine.getInstance(context)
  val settings=AppSettings.get(context)
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val port=object: SessionQueuePort {
   override val state=engine.playbackState
   override fun remove(index: Int){if(index in 0 until engine.player.mediaItemCount)engine.removeFromQueue(index)}
   override fun append(track: Track)=engine.addToQueue(track)
   override fun next()=engine.playNext()
   private var shuffleSignature=""
   override fun actualNext(): Track? {
    val p=engine.player;val index=p.nextMediaItemIndex
    return if(index in 0 until p.mediaItemCount)engine.playbackState.value.queue.firstOrNull {it.id==p.getMediaItemAt(index).mediaId} else null
   }
   override fun alignShuffle(preferredId: String?){
    val player=engine.player
    val preferred=(0 until player.mediaItemCount).firstOrNull {it>player.currentMediaItemIndex && player.getMediaItemAt(it).mediaId==preferredId}
        ?: player.nextMediaItemIndex.takeIf {it>player.currentMediaItemIndex && it<player.mediaItemCount}
    val signature="${player.currentMediaItemIndex}:$preferred:"+(0 until player.mediaItemCount).joinToString(","){player.getMediaItemAt(it).mediaId}
    if(signature!=shuffleSignature){shuffleSignature=signature;player.setShuffleOrder(forwardShuffleOrder(player.mediaItemCount,player.currentMediaItemIndex,signature.hashCode().toLong(),preferred))}
   }
   override fun prefetch(track: Track){
    val p=engine.player
    val target=if(p.shuffleModeEnabled && p.nextMediaItemIndex in 0 until p.mediaItemCount)MusicCatalog.getTrackById(p.getMediaItemAt(p.nextMediaItemIndex).mediaId) ?: track else track
    InnerTubeExtractor.get(context).prefetch(listOf(target))
   }
  }
  val client=YouTubeMusicClient(musicFirst={settings.state.value.musicFirst})
  val source=object: SessionQueueSource {
   override suspend fun profile(): QueueProfile {
    val activity=TasteActivityReader.read(context);val catalog=MusicCatalog.TRACKS;val preferences=settings.state.value
    val ranked=withContext(Dispatchers.Default){TasteRanker.rank(catalog,catalog,activity.likes,activity.history,activity.feedback,preferences).take(160)}
    return QueueProfile(catalog,ranked,activity,preferences)
   }
   private fun register(page: YouTubeMusicClient.CatalogPage): List<Track> {
    MusicCatalog.putTracks(page.tracks);MusicCatalog.putAlbums(page.albums);MusicCatalog.putArtists(page.artists)
    com.mousike.app.data.catalog.CatalogCache.saveSoon(context)
    return page.tracks
   }
   override suspend fun related(seed: Track)=withContext(Dispatchers.IO){if(com.mousike.app.RuntimeMode.offlineFixture || seed.streamUrl.startsWith("asset:"))emptyList() else register(client.related(seed.videoId))}
   override suspend fun discover(seed: Track)=withContext(Dispatchers.IO){
    if(com.mousike.app.RuntimeMode.offlineFixture || seed.streamUrl.startsWith("asset:"))return@withContext emptyList<Track>()
    val q=listOf(seed.artist,seed.genre.takeUnless {it.equals("Music",true)}.orEmpty(),"songs").filter {it.isNotBlank()}.joinToString(" ").take(140)
    val result=client.search(q);MusicCatalog.putTracks(result.tracks);result.tracks
   }
  }
  val store=AndroidQueueStore(context)
  val controller=SessionQueueController(scope,port,source,store)
  scope.launch {settings.state.map {listOf(it.autoplay,it.preloadNext,it.discovery,it.artists,it.genres,it.languages,it.musicFirst)}.distinctUntilChanged().drop(1).debounce(250).collect {controller.refresh(force=true)}}
  return controller
 }
}

private class AndroidQueueStore(context: Context): SessionQueueStore {
 private val prefs=context.getSharedPreferences("mousike_session_flow_v1",Context.MODE_PRIVATE)
 override suspend fun load(): QueueCheckpoint?=withContext(Dispatchers.IO){runCatching {
  val raw=prefs.getString("session",null) ?: return@withContext null
  val root=JSONObject(raw)
  fun strings(o: JSONObject,name: String): List<String> {val a=o.optJSONArray(name) ?: return emptyList();return (0 until a.length()).map {a.optString(it)}.filter {it.isNotBlank()}.takeLast(500)}
  val i=root.optJSONObject("intent")?.let {o->QueueIntent(ListeningMode.valueOf(o.getString("mode")),TrackCodec.decode(o.getJSONObject("seed")),o.optJSONArray("accepted")?.let {a->(0 until a.length()).map {TrackCodec.decode(a.getJSONObject(it))}.takeLast(8)}.orEmpty(),strings(o,"rejected").toSet(),strings(o,"seen").toSet(),strings(o,"sources").toSet())}
  val a=root.optJSONArray("recent") ?: JSONArray()
  val recent=(maxOf(0,a.length()-400) until a.length()).map {index->val o=a.getJSONObject(index);RecentExposure(o.getString("id"),o.getString("key"),o.getString("source"),o.getLong("at"),o.optBoolean("skip"))}
  QueueCheckpoint(i,root.optString("current"),recent,strings(root,"manual"),strings(root,"automatic").toSet(),root.optInt("retained",32).coerceIn(32,500),root.optBoolean("suspended"),root.optBoolean("dynamic",true))
 }.getOrNull()}
 override suspend fun save(value: QueueCheckpoint)=withContext(Dispatchers.IO){
  val root=JSONObject().put("current",value.currentId).put("manual",JSONArray(value.manual.take(500))).put("automatic",JSONArray(value.automatic)).put("retained",value.retainedPast).put("suspended",value.autoSuspended).put("dynamic",value.dynamic)
  value.intent?.let {i->root.put("intent",JSONObject().put("mode",i.mode.name).put("seed",TrackCodec.encode(i.seed)).put("accepted",JSONArray().apply {i.accepted.takeLast(8).forEach {put(TrackCodec.encode(it))}}).put("rejected",JSONArray(i.rejected)).put("seen",JSONArray(i.seen)).put("sources",JSONArray(i.seenSources)))}
  root.put("recent",JSONArray().apply {value.recent.takeLast(400).forEach {put(JSONObject().put("id",it.id).put("key",it.key).put("source",it.source).put("at",it.at).put("skip",it.earlySkip))}})
  prefs.edit().putString("session",root.toString()).apply()
 }
}
