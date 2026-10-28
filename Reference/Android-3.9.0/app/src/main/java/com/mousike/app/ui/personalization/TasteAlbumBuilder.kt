package com.mousike.app.ui.personalization

import com.mousike.app.data.local.HistoryEntity
import com.mousike.app.data.local.FeedbackEntity
import com.mousike.app.data.model.Track
import com.mousike.app.recommendation.TasteRanker
import com.mousike.app.settings.Preferences
import kotlin.math.exp
import kotlin.math.ln

data class TasteActivity(val likes: Set<String>,val history: List<HistoryEntity>,val feedback: List<FeedbackEntity>)
data class TasteAlbumResult(val tracks: List<Track>,val reasons: Map<String,String>,val signals: Int,
    val completions: Int,val earlySkips: Int,val likes: Int,val sounds: List<String>,val hasActivity: Boolean)

/** A user-requested presentation collection. Does not alter autoplay, playback or global ranking. */
object TasteAlbumBuilder {
    const val ALBUM_ID="personal-build-my-taste"
    private val longRecording=Regex("(?i)full album|jukebox|compilation|non.stop mix")
    private val traditions=listOf("Sufi","Ghazal","Qawwali","Folk","Devotional","Carnatic","Hindustani","Bhajan","Abhang")
    fun build(catalog: List<Track>,activity: TasteActivity,prefs: Preferences,limit: Int=30,now: Long=System.currentTimeMillis()): TasteAlbumResult {
        val byId=catalog.associateBy {it.id}
        val weights=hashMapOf<String,Double>();val skips=hashMapOf<String,Int>();val completed=hashSetOf<String>()
        var completions=0;var earlySkips=0
        for(h in activity.history) {
            val decay=exp(-(now-h.playedAt).coerceAtLeast(0)/86400000.0/45.0)
            val weight=when {
                h.wasSkipped && h.completionPercent<25 -> {earlySkips++;skips[h.trackId]=(skips[h.trackId] ?: 0)+1;-2.4}
                h.wasSkipped -> -.7
                h.completionPercent>=80 -> {completions++;completed.add(h.trackId);1.8}
                h.completionPercent>=50 -> .65
                h.completionPercent>=25 -> .18
                else -> 0.0 // A zero-percent start record is not a dislike.
            }
            weights[h.trackId]=((weights[h.trackId] ?: 0.0)+weight*decay).coerceIn(-7.0,6.0)
        }
        activity.likes.forEach {weights[it]=(weights[it] ?: 0.0)+3.5}
        val latest=activity.feedback.sortedByDescending {it.timestamp}.distinctBy {it.trackId}
        val blocked=latest.filter {it.feedbackType in setOf("DONT_RECOMMEND","NOT_INTERESTED")}.map {it.trackId}.toSet()
        val blockedArtists=latest.filter {it.feedbackType=="LESS_OF_ARTIST"}.map {it.artistName.lowercase()}.toSet()
        latest.filter {it.feedbackType=="MORE_LIKE_THIS"}.forEach {weights[it.trackId]=(weights[it.trackId] ?: 0.0)+2.5}
        val artist=hashMapOf<String,Double>();val language=hashMapOf<String,Double>();val genre=hashMapOf<String,Double>();val mood=hashMapOf<String,Double>();val regional=hashMapOf<String,Double>()
        fun add(map: MutableMap<String,Double>,key: String,w: Double){if(key.isNotBlank() && key!="Music")map[key]=(map[key] ?: 0.0)+w}
        fun tags(t: Track)=traditions.filter {"${t.genre} ${t.mood} ${t.title} ${t.album}".contains(it,true)}
        weights.forEach {(id,w)->byId[id]?.let {t->
            add(artist,t.artist,w);add(language,t.language,w);add(genre,t.genre,w);add(mood,t.mood,w)
            tags(t).forEach {add(regional,it,w)}
        }}
        fun affinity(map: Map<String,Double>,key: String)=map[key]?.let {if(it>0)ln(1+it) else -ln(1-it)} ?: 0.0
        val eligible=catalog.filter {t->t.id !in blocked && t.artist.lowercase() !in blockedArtists &&
            (t.endMs!=null || t.durationSec<=480 && !longRecording.containsMatchIn(t.title)) &&
            !((skips[t.id] ?: 0)>=2 && t.id !in activity.likes)}.distinctBy {TasteRanker.canonical(it)}
        val ranked=eligible.map {t->
            var score=1.0+affinity(artist,t.artist)*2.2+affinity(language,t.language)*.65+affinity(genre,t.genre)*.65+affinity(mood,t.mood)*.85
            score+=tags(t).sumOf {affinity(regional,it)}*.6
            score+=(weights[t.id] ?: 0.0)*.6
            if(prefs.artists.any {t.artist.contains(it,true)})score+=2.0
            if(prefs.languages.any {it.equals(t.language,true)})score+=.75
            if(prefs.genres.any {"${t.genre} ${t.title} ${t.album}".contains(it,true)})score+=.9
            // Small deterministic variation, never a replacement for listening evidence.
            score+=((t.id+now/86400000).hashCode().toLong() and 0xffff)/655350.0
            t to score
        }.sortedByDescending {it.second}.take(400).toMutableList()
        val selected=mutableListOf<Track>();val counts=hashMapOf<String,Int>()
        while(selected.size<limit.coerceIn(1,50) && ranked.isNotEmpty()) {
            val diverse=ranked.filter {(counts[it.first.artist] ?: 0)<4 && it.first.artist!=selected.lastOrNull()?.artist}
            val pool=diverse.ifEmpty {ranked.filter {it.first.artist!=selected.lastOrNull()?.artist}}.ifEmpty {ranked}
            val pick=pool.maxBy {it.second-(counts[it.first.artist] ?: 0)*1.6}
            selected.add(pick.first);counts[pick.first.artist]=(counts[pick.first.artist] ?: 0)+1;ranked.remove(pick)
        }
        val sounds=(regional.entries+language.entries+mood.entries).filter {it.value>0 && it.key.isNotBlank()}.sortedByDescending {it.value}.map {it.key}.distinct().take(4)
            .ifEmpty {(prefs.languages+prefs.genres).take(4)}
        val reasons=selected.associate {t->t.id to when {
            t.id in activity.likes -> "One of your favourites"
            t.id in completed -> "You listened all the way through"
            affinity(artist,t.artist)>0 -> "An artist you return to"
            tags(t).any {affinity(regional,it)>0} -> "A regional sound you enjoy"
            affinity(mood,t.mood)>0 -> "In tune with your listening mood"
            else -> "A discovery shaped by your preferences"
        }}
        return TasteAlbumResult(selected,reasons,activity.history.size,completions,earlySkips,activity.likes.size,sounds,
            activity.likes.isNotEmpty() || activity.history.any {it.completionPercent>0 || it.wasSkipped})
    }
}
