package com.mousike.app.session

import com.mousike.app.data.model.Track
import com.mousike.app.settings.Preferences
import com.mousike.app.ui.personalization.TasteActivity
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Queue selection only. No URL resolution, playback, network or database operations. */
enum class ListeningMode { HOME, SEARCH }
data class RecentExposure(val id: String,val key: String,val source: String,val at: Long,val earlySkip: Boolean=false)
data class QueueProfile(val catalog: List<Track>,val ranked: List<Track>,val activity: TasteActivity,val preferences: Preferences)
data class QueueIntent(val mode: ListeningMode,val seed: Track,val accepted: List<Track> = emptyList(),val rejected: Set<String> = emptySet(),val seen: Set<String> = emptySet(),val seenSources: Set<String> = emptySet())

object SessionQueuePolicy {
 const val HORIZON=8
 const val RECENT_LIMIT=400
 private val space=Regex("\\s+")
 private val wrappers=Regex("(?i)\\b(official|audio|video|lyrics|lyrical|hd|4k|remastered|full song)\\b")
 private val fromMovie=Regex("(?i)\\(?from\\s+[\"'].+?[\"']\\)?")
 private val channel=Regex("(?i)(?:[-–]\\s*)?\\b(topic|vevo|official)\\b")
 private val nonSong=Regex("(?i)\\b(full\\s+(album|movie|concert)|jukebox|compilation|non[ -]?stop|podcast|interview|reaction|tutorial|gameplay|trailer|teaser|livestream|\\d+\\s*(hours?|hrs?))\\b")
 private val bland=setOf("music","song","songs","official","audio","video","the","and","from","feat","with","love","new","best","hits")
 fun norm(s: String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]")," ").trim().replace(space," ")
 fun artist(t: Track)=norm(t.artist.replace(channel,""))
 fun key(t: Track): String {
  val a=artist(t);var title=norm(t.title.replace(fromMovie,"").replace(wrappers,""))
  if(a.isNotBlank() && title.startsWith("$a "))title=title.removePrefix("$a ")
  return "$a:$title"
 }
 fun source(t: Track)=if(t.endMs!=null)"${t.videoId}@${t.startMs}:${t.endMs}" else t.videoId
 fun duration(t: Track): Long=if(t.endMs!=null)(t.endMs-t.startMs)/1000 else t.durationSec.toLong()
 fun eligible(t: Track): Boolean=duration(t) in 35..480 && t.startMs>=0 && t.title.isNotBlank() && artist(t).isNotBlank() &&
  artist(t)!="unknown artist" && !t.title.equals("Loading music",true) && !nonSong.containsMatchIn(t.title)
 private fun meaningful(s: String)=norm(s).takeUnless {it in setOf("","music","unknown","various artists")}
 private fun words(t: Track)=norm("${t.title} ${t.album} ${t.genre} ${t.mood}").split(' ').filter {it.length>2 && it !in bland}.toSet()
 /** Missing language/BPM/default Music tags are not evidence of a related song. */
 fun similarity(a: Track,b: Track): Double {
  if(artist(a)==artist(b))return 1.0
  if(meaningful(a.album)!=null && norm(a.album)==norm(b.album))return .95
  var score=0.0
  if(meaningful(a.genre)!=null && norm(a.genre)==norm(b.genre))score+=.55
  if(meaningful(a.mood)!=null && norm(a.mood)==norm(b.mood))score+=.65
  val aw=words(a);val bw=words(b);val union=(aw+bw).size
  if(union>0)score+=((aw intersect bw).size.toDouble()/union)*.65
  return score.coerceAtMost(1.0)
 }
 fun plan(intent: QueueIntent,current: Track,profile: QueueProfile,related: List<Track>,exposures: List<RecentExposure>,reserved: List<Track> = emptyList(),limit: Int=HORIZON,now: Long=System.currentTimeMillis()): List<Track> {
  val catalog=(profile.catalog+profile.ranked+related+intent.accepted+intent.seed+current).distinctBy {it.id}
  val byId=catalog.associateBy {it.id};val activity=profile.activity
  val latest=activity.feedback.sortedByDescending {it.timestamp}.distinctBy {it.trackId}
  val blockedIds=latest.filter {it.feedbackType in setOf("NOT_INTERESTED","DONT_RECOMMEND")}.map {it.trackId}.toSet()
  val blockedKeys=blockedIds.mapNotNull {byId[it]?.let(::key)}.toSet()+intent.rejected+intent.seen
  val suppressed=latest.filter {it.feedbackType=="LESS_OF_ARTIST"}.map {norm(it.artistName.replace(channel,""))}.toSet()
  val recent=exposures.filter {now-it.at < (if(it.earlySkip)24 else 6)*3600000L}
  val recentIds=recent.map {it.id}.toSet()+activity.history.filter {now-it.playedAt<(if(it.wasSkipped && it.completionPercent<25)24 else 6)*3600000L}.map {it.trackId}
  val recentKeys=recent.map {it.key}.toSet()+recentIds.mapNotNull {byId[it]?.let(::key)}
  val recentSources=recent.map {it.source}.toSet()+intent.seenSources
  val reservedKeys=reserved.map(::key).toSet()+key(current);val reservedSources=reserved.map(::source).toSet()+source(current)
  val skippedArtists=intent.rejected.groupingBy {it.substringBefore(':')}.eachCount()
  val artistAffinity=hashMapOf<String,Double>();val genreAffinity=hashMapOf<String,Double>();val moodAffinity=hashMapOf<String,Double>()
  fun add(t: Track,w: Double){artistAffinity[artist(t)]=(artistAffinity[artist(t)] ?: 0.0)+w;meaningful(t.genre)?.let {genreAffinity[it]=(genreAffinity[it] ?: 0.0)+w};meaningful(t.mood)?.let {moodAffinity[it]=(moodAffinity[it] ?: 0.0)+w}}
  activity.likes.forEach {byId[it]?.let {t->add(t,1.0)}}
  activity.history.take(2000).forEach {h->val w=when {h.wasSkipped && h.completionPercent<25 -> -1.8;h.completionPercent>=75 -> 1.0;h.completionPercent>=35 -> .3;else -> 0.0};byId[h.trackId]?.let {add(it,w*exp(-(now-h.playedAt).coerceAtLeast(0)/86400000.0/30))}}
  intent.accepted.takeLast(6).forEach {add(it,1.3)}
  fun affinity(map: Map<String,Double>,s: String)=((map[s] ?: 0.0).let {if(it>=0)ln(1+it) else -ln(1-it)}).coerceIn(-2.0,2.0)
  val relatedPositions=related.mapIndexed {i,t->key(t) to i}.toMap()
  val rankedPositions=profile.ranked.mapIndexed {i,t->key(t) to i}.toMap()
  val heard=activity.history.map {it.trackId}.toSet()
  val candidates=(related+profile.ranked+profile.catalog).filter {t->eligible(t) && t.id !in blockedIds && key(t) !in blockedKeys && artist(t) !in suppressed && t.id !in recentIds && key(t) !in recentKeys && source(t) !in recentSources && key(t) !in reservedKeys && source(t) !in reservedSources}
   .distinctBy(::key).distinctBy(::source).mapNotNull {t->
    val seed=similarity(intent.seed,t);val relatedPosition=relatedPositions[key(t)]
    if(intent.mode==ListeningMode.SEARCH && relatedPosition==null && seed<.4)return@mapNotNull null
    var taste=affinity(artistAffinity,artist(t))*.8+affinity(genreAffinity,norm(t.genre))*.5+affinity(moodAffinity,norm(t.mood))*.5
    taste-=(skippedArtists[artist(t)] ?: 0)*1.6
    if(profile.preferences.artists.any {norm(it)==artist(t)})taste+=.7
    if(profile.preferences.languages.any {it.equals(t.language,true)})taste+=.3
    if(profile.preferences.genres.any {it.equals(t.genre,true)})taste+=.45
    var score=if(intent.mode==ListeningMode.SEARCH)seed*3.2+taste*.65+(if(relatedPosition!=null)3.0/(1+relatedPosition*.06) else 0.0) else taste*1.2+similarity(current,t)*.8+seed*.3+(if(relatedPosition!=null).8 else 0.0)
    score+=rankedPositions[key(t)]?.let { .6/(1+it*.04) } ?: 0.0
    if(t.id !in heard && t.id !in activity.likes)score+=.8+profile.preferences.discovery*.6
    if(profile.preferences.musicFirst && t.musicVideoType=="MUSIC_VIDEO_TYPE_ATV")score+=.3
    if(t.musicVideoType=="MUSIC_VIDEO_TYPE_UGC")score-=.5
    // Stable per-session/anchor variation only breaks otherwise similar scores.
    score+=((t.id+intent.seed.id+current.id+now/86400000).hashCode().toLong() and 0xffff)/655350.0
    t to score
   }.sortedByDescending {it.second}.take(360).toMutableList()
  val selected=mutableListOf<Track>();val counts=(intent.accepted.takeLast(4)+reserved).groupingBy(::artist).eachCount().toMutableMap()
  var familiar=reserved.count {it.id in activity.likes || it.id in heard}
  var previous=artist(reserved.lastOrNull() ?: current)
  while(candidates.isNotEmpty() && selected.size<limit.coerceIn(0,16)) {
   val fresh=candidates.filter {it.first.id !in activity.likes && it.first.id !in heard}
   val pool=if(familiar>=2 && fresh.isNotEmpty())fresh else candidates
   val varied=pool.filter {artist(it.first)!=previous}.ifEmpty {pool}
   val pick=varied.maxBy {it.second-(counts[artist(it.first)] ?: 0)*1.65-selected.count {s->s.album.isNotBlank() && norm(s.album)==norm(it.first.album)}*.9}
   candidates.remove(pick);selected.add(pick.first);previous=artist(pick.first);counts[previous]=(counts[previous] ?: 0)+1
   if(pick.first.id in activity.likes || pick.first.id in heard)familiar++
  }
  return selected
 }
}
