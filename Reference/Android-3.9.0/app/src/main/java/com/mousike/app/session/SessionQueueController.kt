package com.mousike.app.session

import com.mousike.app.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface SessionQueuePort {
 val state: StateFlow<PlaybackState>
 fun remove(index: Int)
 fun append(track: Track)
 fun next()
 fun actualNext(): Track?=state.value.queue.getOrNull(state.value.queueIndex+1)
 fun alignShuffle(preferredId: String?=null) {}
 fun prefetch(track: Track)
}
interface SessionQueueSource {
 suspend fun profile(): QueueProfile
 suspend fun related(seed: Track): List<Track>
 suspend fun discover(seed: Track): List<Track>
}
data class QueueCheckpoint(val intent: QueueIntent?,val currentId: String,val recent: List<RecentExposure>,val manual: List<String>,val automatic: Set<String>,val retainedPast: Int=32,val autoSuspended: Boolean=false,val dynamic: Boolean=true)
interface SessionQueueStore {suspend fun load(): QueueCheckpoint?;suspend fun save(value: QueueCheckpoint)}
data class SessionQueueStatus(val mode: ListeningMode?=null,val title: String="",val refreshing: Boolean=false,val exhausted: Boolean=false)

/** Application-scoped planning, independent of the Activity and stream transport. All mutations are on the supplied main scope. */
class SessionQueueController(private val scope: CoroutineScope,private val port: SessionQueuePort,private val source: SessionQueueSource,private val store: SessionQueueStore,private val now: ()->Long=System::currentTimeMillis,private val worker: CoroutineDispatcher=Dispatchers.Default) {
 private var intent: QueueIntent?=null
 private var expectedId=""
 private var generation=0L
 private var profile: QueueProfile?=null
 private val recent=mutableListOf<RecentExposure>()
 private val manual=mutableListOf<String>()
 private val automatic=mutableSetOf<String>()
 private val relatedCache=linkedMapOf<String,Pair<Long,List<Track>>>()
 private var related=emptyList<Track>()
 private var observed: Track?=null
 private var position=0L
 private var listened=0L
 private var played=false
 private var wasPlaying=false
 private var fingerprint=""
 private var planJob: Job?=null
 private var reconcileJob: Job?=null
 private var persistJob: Job?=null
 private var pendingNext=false
 private var lastFetchAt=0L
 private var lastFetchAnchor=""
 private var forceSelection=false
 private var lastPrefetch=""
 private var retainedPast=32
 private var wants=false
 private var autoSuspended=false
 private var interrupted=false
 private var freshIntent=false
 private var dynamic=true
 private var observedIndex=0
 private val mutable=MutableStateFlow(SessionQueueStatus())
 val status: StateFlow<SessionQueueStatus> = mutable
 private val messages=MutableSharedFlow<String>(extraBufferCapacity=8)
 val notices: SharedFlow<String> = messages.asSharedFlow()
 init {
  scope.launch {
   val before=generation
   val saved=runCatching {store.load()}.getOrNull()
   if(saved!=null){recent.addAll(saved.recent.filter {now()-it.at<72*3600000L});trimRecent()
    if(before==generation && generation==0L && saved.currentId==port.state.value.currentTrack?.id){intent=saved.intent;expectedId=saved.currentId;manual.addAll(saved.manual);automatic.addAll(saved.automatic);retainedPast=saved.retainedPast;autoSuspended=saved.autoSuspended;dynamic=saved.dynamic;publish()}}
   runCatching {source.profile()}.onSuccess {profile=it}
   if(intent!=null)refresh()
  }
  scope.launch {port.state.collect {observe(it)}}
 }
 private fun publish(refreshing: Boolean=false,exhausted: Boolean=false){val i=intent;mutable.value=SessionQueueStatus(i?.mode,when(i?.mode){ListeningMode.HOME->"From your taste";ListeningMode.SEARCH->"Related to ${i.seed.title}";null->""},refreshing,exhausted)}
 private fun trimRecent(){while(recent.size>SessionQueuePolicy.RECENT_LIMIT)recent.removeAt(0)}
 private fun exposure(t: Track,skip: Boolean){recent.removeAll {it.id==t.id};recent.add(RecentExposure(t.id,SessionQueuePolicy.key(t),SessionQueuePolicy.source(t),now(),skip));trimRecent()}
 private fun save(){val checkpoint=QueueCheckpoint(intent,port.state.value.currentTrack?.id.orEmpty(),recent.toList(),manual.take(500),automatic.toSet(),retainedPast,autoSuspended,dynamic);persistJob?.cancel();persistJob=scope.launch {delay(150);runCatching {store.save(checkpoint)}}}
 /** Called before the existing engine play command. Uses at most 100 cached candidates; no I/O. */
 fun begin(mode: ListeningMode,seed: Track,ordered: List<Track>?=null): List<Track> {
  finishObserved();generation++;planJob?.cancel();reconcileJob?.cancel();pendingNext=false
  autoSuspended=false;freshIntent=true
  intent=QueueIntent(mode,seed);expectedId=seed.id;manual.clear();automatic.clear();related=relatedCache[seed.videoId]?.takeIf {now()-it.first<600000}?.second.orEmpty()
  observed=null;position=0;listened=0;played=false;wasPlaying=false;fingerprint="";lastPrefetch="";forceSelection=false
  dynamic=ordered==null
  retainedPast=ordered?.size?.coerceIn(32,500) ?: 32
  if(ordered!=null){val index=ordered.indexOfFirst {it.id==seed.id}.coerceAtLeast(0);manual.addAll(ordered.drop(index+1).map {it.id});publish();return ordered}
  val p=profile
  val tail=if(p!=null && p.preferences.autoplay)SessionQueuePolicy.plan(intent!!,seed,p.copy(catalog=p.ranked.take(100),ranked=p.ranked.take(100)),related,recent,limit=SessionQueuePolicy.HORIZON) else emptyList()
  automatic.addAll(tail.map {it.id});publish();return listOf(seed)+tail
 }
 fun leave(){generation++;intent=null;expectedId="";planJob?.cancel();reconcileJob?.cancel();manual.clear();automatic.clear();pendingNext=false;observed=null;publish();save()}
 fun markManual(t: Track){if(intent!=null)manual.add(t.id)}
 fun removeManual(id: String){manual.remove(id)}
 fun acceptManualOrder(){
  if(intent==null)return
  generation++;planJob?.cancel();planJob=null
  manual.clear();manual.addAll(port.state.value.queue.drop(port.state.value.queueIndex+1).map {it.id});automatic.clear();save()
 }
 fun userClearedQueue(){generation++;autoSuspended=true;planJob?.cancel();planJob=null;manual.clear();automatic.clear();pendingNext=false;publish(exhausted=true);save()}
 fun clearListeningMemory(){recent.clear();intent=intent?.copy(accepted=emptyList(),rejected=emptySet(),seen=emptySet(),seenSources=emptySet());listened=0;save();refresh(force=true)}
 fun reset(){leave();recent.clear();relatedCache.clear();profile=null;save()}
 fun refresh(force: Boolean=false){
  if(force){generation++;planJob?.cancel();planJob=null}
  if(intent==null){planJob=scope.launch {runCatching {source.profile()}.onSuccess {profile=it}};return}
  forceSelection=forceSelection || force;schedulePlan()
 }
 fun next(): Boolean {
  if(intent==null)return false
  autoSuspended=false
  reconcile()
  val s=port.state.value
  if(s.queueIndex<s.queue.lastIndex){port.next();return true}
  pendingNext=true;schedulePlan(forceNetwork=true);return true
 }
 private fun finishObserved(){
  val t=observed ?: return
  val sec=SessionQueuePolicy.duration(t).takeIf {it>0} ?: (port.state.value.durationMs/1000).coerceAtLeast(1)
  val percent=(listened*100/(sec*1000)).coerceIn(0,100)
  val skip=played && !interrupted && listened<30000 && percent<25
  exposure(t,skip)
  intent=intent?.let {i->when {
   skip -> i.copy(rejected=(i.rejected+SessionQueuePolicy.key(t)).takeLastSet(80))
   percent>=50 || listened>=90000 -> i.copy(accepted=(i.accepted+t).takeLast(8))
   else->i
  }}
  if(skip)forceSelection=true
 }
 private fun observe(s: PlaybackState){
  val i=intent ?: return
  val becamePlaying=s.isPlaying && !wasPlaying
  if(wants && !s.playWhenReady && !s.isPlaying)pendingNext=false
  wants=s.playWhenReady
  val t=s.currentTrack ?: return
  if(observed==null && t.id!=expectedId)return
  if(observed?.id!=t.id){
   if(observed!=null && s.queueIndex<observedIndex)interrupted=true
   finishObserved();planJob?.cancel();planJob=null;manual.remove(t.id);observed=t;expectedId=t.id;position=s.currentPositionMs;listened=0;played=s.isPlaying;wasPlaying=s.isPlaying;interrupted=s.isBuffering || s.error!=null
   exposure(t,false)
   intent=intent?.copy(seen=(intent!!.seen+SessionQueuePolicy.key(t)).takeLastSet(400),seenSources=(intent!!.seenSources+SessionQueuePolicy.source(t)).takeLastSet(400))
   save();schedulePlan()
  } else {
   val delta=s.currentPositionMs-position
   if(wasPlaying && delta in 1..1500)listened+=delta
   position=s.currentPositionMs;wasPlaying=s.isPlaying;interrupted=s.isBuffering || s.error!=null;if(s.isPlaying)played=true
  }
  observedIndex=s.queueIndex
  val key="${s.currentTrack.id}:${s.queueIndex}:${s.isShuffle}:"+s.queue.joinToString(","){it.id}
  if(key!=fingerprint){fingerprint=key
   reconcileJob?.cancel();reconcileJob=scope.launch {yield();reconcile();if(port.state.value.queue.size-port.state.value.queueIndex<=4)schedulePlan()}
  }
  if(becamePlaying && related.isEmpty())schedulePlan()
 }
 /** Emissions precede Media3 mutations in the unchanged engine; callers defer this to a later main-loop turn. */
 private fun manualIndices(s: PlaybackState): Set<Int> {
  val claims=manual.groupingBy {it}.eachCount().toMutableMap();val out=mutableSetOf<Int>()
  for(index in s.queueIndex+1..s.queue.lastIndex){val id=s.queue[index].id;val count=claims[id] ?: 0;if(count>0){out.add(index);claims[id]=count-1}}
  return out
 }
 private fun reconcile(){
  val i=intent ?: return;val s=port.state.value;val current=s.currentTrack ?: return
  if(current.id!=expectedId)return
  val protected=manualIndices(s);val keys=mutableSetOf(SessionQueuePolicy.key(current));val sources=mutableSetOf(SessionQueuePolicy.source(current));val remove=mutableListOf<Int>();var count=0
  for(index in s.queueIndex+1..s.queue.lastIndex){val t=s.queue[index]
   if(index in protected){keys.add(SessionQueuePolicy.key(t));sources.add(SessionQueuePolicy.source(t));continue}
   val valid=t.id in automatic && SessionQueuePolicy.eligible(t) && SessionQueuePolicy.key(t) !in i.seen && SessionQueuePolicy.source(t) !in i.seenSources && keys.add(SessionQueuePolicy.key(t)) && sources.add(SessionQueuePolicy.source(t)) && count++<SessionQueuePolicy.HORIZON
   if(!valid)remove.add(index)
  }
  remove.asReversed().forEach {port.remove(it)}
  val excess=port.state.value.queueIndex-retainedPast
  if(excess>0)for(index in excess-1 downTo 0)port.remove(index)
  if(dynamic && port.state.value.isShuffle)port.alignShuffle()
 }
 private fun schedulePlan(forceNetwork: Boolean=false){
  if(intent==null || autoSuspended || planJob?.isActive==true)return
  val token=generation;val trackId=port.state.value.currentTrack?.id ?: return
  planJob=scope.launch {
   yield();if(token!=generation || trackId!=expectedId)return@launch
   publish(refreshing=true)
   try {
    val p=source.profile();if(token!=generation || trackId!=expectedId)return@launch;profile=p
    rebuild(p)
    val s=port.state.value;val protected=manualIndices(s)
    val anchor=intent!!.let {if(it.mode==ListeningMode.SEARCH)it.seed else s.currentTrack!!}
    val entry=relatedCache[anchor.videoId]
    val freshCached=if(entry!=null)withContext(worker){SessionQueuePolicy.plan(intent!!,s.currentTrack!!,p.copy(catalog=emptyList(),ranked=emptyList()),entry.second,recent.toList(),limit=1).isNotEmpty()} else false
    val cached=entry?.takeIf {now()-it.first<(if(freshCached)600000 else 20000)}?.takeUnless {forceNetwork}
    if(cached!=null){related=cached.second;rebuild(p)}
    val need=(s.isPlaying || pendingNext) && (p.preferences.autoplay || pendingNext) && manual.size<=2 && protected.size==manual.size && (related.size<16 || intent!!.accepted.size%3==0)
    if(need && cached==null && (freshIntent || now()-lastFetchAt>=(if(forceNetwork)3000 else 20000))) {
     if(freshIntent)delay((3000-(now()-lastFetchAt)).coerceAtLeast(0))
     if(token!=generation || trackId!=expectedId)return@launch
     freshIntent=false;lastFetchAt=now();lastFetchAnchor=anchor.videoId
     val found=try {source.related(anchor)}catch(e: CancellationException){throw e}catch(_: Exception){emptyList()}
     if(token!=generation || trackId!=expectedId)return@launch
     val enough=withContext(worker){SessionQueuePolicy.plan(intent!!,s.currentTrack!!,p,found,recent.toList(),limit=4).size>=4}
     val discovered=if(!enough)try {source.discover(anchor)}catch(e: CancellationException){throw e}catch(_: Exception){emptyList()} else emptyList()
     if(token!=generation || trackId!=expectedId)return@launch
     related=(found+discovered).distinctBy {it.id}.take(100)
     relatedCache.remove(anchor.videoId);relatedCache[anchor.videoId]=now() to related
     while(relatedCache.size>12)relatedCache.remove(relatedCache.keys.first())
     val fresh=source.profile();if(token!=generation || trackId!=expectedId)return@launch;profile=fresh;rebuild(fresh)
    }
   } catch(e: CancellationException){throw e}
   catch(_: Exception){/* Cached safe queue stays playable; no retry loop or random fallback. */}
   finally {if(token==generation && trackId==expectedId){val s=port.state.value;val empty=s.queueIndex>=s.queue.lastIndex;if(empty && pendingNext)messages.tryEmit(if(intent?.mode==ListeningMode.SEARCH)"No fresh related songs are available yet. Choose another song or try Next again." else "No fresh recommendations are available yet. Choose another song or try Next again.");if(empty)pendingNext=false;publish(exhausted=empty);save()}}
  }
 }
 private suspend fun rebuild(p: QueueProfile){
  val i=intent ?: return;val s=port.state.value;val current=s.currentTrack ?: return;val token=generation
  val protected=manualIndices(s);val reserved=protected.map {s.queue[it]}+s.queue.take(s.queueIndex)
  val manualPrefix=manual.size>protected.size
  val planned=if(!manualPrefix && (p.preferences.autoplay || pendingNext))withContext(worker){SessionQueuePolicy.plan(i,current,p,related,recent.toList(),reserved)} else emptyList()
  if(token!=generation || port.state.value.currentTrack?.id!=current.id)return
  val live=port.state.value;val manualNow=manualIndices(live)
  val next=port.actualNext()
  val keep=next?.takeIf {!forceSelection && manualNow.isEmpty() && it.id in automatic && planned.any {candidate->candidate.id==it.id}}
  val protectedKeys=manualNow.map {SessionQueuePolicy.key(live.queue[it])}.toSet()
  val tail=(listOfNotNull(keep)+planned).distinctBy(SessionQueuePolicy::key).filter {SessionQueuePolicy.key(it) !in protectedKeys}.take(SessionQueuePolicy.HORIZON)
  val expected=manualNow.map {live.queue[it]}+tail
  val actual=live.queue.drop(live.queueIndex+1)
  if(actual.map {it.id}!=expected.map {it.id}){
   // Retain the already prepared immediate next item when it is still a good match.
   val keepIndex=keep?.let {t->live.queue.indexOfFirst {it.id==t.id}}
   for(index in live.queue.lastIndex downTo live.queueIndex+1)if(index !in manualNow && index!=keepIndex)port.remove(index)
   automatic.clear();automatic.addAll(tail.map {it.id})
   tail.filter {it.id!=keep?.id}.forEach {port.append(it)}
  } else {automatic.clear();automatic.addAll(tail.map {it.id})}
  forceSelection=false
  if(dynamic && port.state.value.isShuffle)port.alignShuffle(keep?.id)
  val ready=port.state.value;val upNext=ready.queue.getOrNull(ready.queueIndex+1)
  if(ready.isPlaying && p.preferences.preloadNext && upNext!=null && lastPrefetch!=upNext.id){lastPrefetch=upNext.id;port.prefetch(upNext)}
  if(pendingNext && upNext!=null){pendingNext=false;port.next()}
 }
 private fun <T> Set<T>.takeLastSet(max: Int)=toList().takeLast(max).toSet()
}
