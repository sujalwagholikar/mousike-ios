package com.mousike.app.ui.personalization

import com.mousike.app.data.model.Artist
import com.mousike.app.data.model.Track
import com.mousike.app.settings.Preferences
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Established discovery seeds, not invented profiles or global listener rankings. */
object ArtistSuggestions {
    private val established=linkedMapOf(
        "Hindi" to listOf("Arijit Singh","Shreya Ghoshal","A R Rahman","Sonu Nigam","Sunidhi Chauhan","Atif Aslam","Pritam","Vishal Mishra","Jubin Nautiyal","Kishore Kumar","Lata Mangeshkar","Mohit Chauhan"),
        "English" to listOf("The Weeknd","Taylor Swift","Billie Eilish","Coldplay","Dua Lipa","Ed Sheeran","Ariana Grande","Bruno Mars","Drake","Sabrina Carpenter","Kendrick Lamar","Arctic Monkeys","Lana Del Rey","Justin Bieber","Adele","Post Malone","Olivia Rodrigo"),
        "Marathi" to listOf("Ajay Atul","Avadhoot Gupte","Bela Shende","Shankar Mahadevan","Shreya Ghoshal"),
        "Punjabi" to listOf("Diljit Dosanjh","Karan Aujla","AP Dhillon","Sidhu Moose Wala","Shubh","Guru Randhawa","B Praak"),
        "Tamil" to listOf("Anirudh Ravichander","A R Rahman","Sid Sriram","S P Balasubrahmanyam","Yuvan Shankar Raja"),
        "Telugu" to listOf("Sid Sriram","S P Balasubrahmanyam","Devi Sri Prasad","Thaman S","Anirudh Ravichander"),
        "Bengali" to listOf("Anupam Roy","Arijit Singh","Shreya Ghoshal","Anjan Dutt"),
        "Korean" to listOf("BTS","BLACKPINK","IU","Jung Kook","Stray Kids","TWICE","SEVENTEEN"),
        "Kannada" to listOf("Vijay Prakash","Sonu Nigam","Shreya Ghoshal"),
        "Malayalam" to listOf("K J Yesudas","K S Chithra","Sushin Shyam"),
        "Spanish" to listOf("Bad Bunny","Shakira","Karol G","Rosalia","Rauw Alejandro"),
        "Japanese" to listOf("YOASOBI","Ado","Kenshi Yonezu"))
    private fun key(value: String)=Normalizer.normalize(value,Normalizer.Form.NFD).lowercase(Locale.ROOT).replace(Regex("\\b(official|topic|vevo)\\b"),"").replace(Regex("[^\\p{L}\\p{N}]"),"")
    fun seeds(languages: Set<String>,preferred: Set<String> = emptySet(),limit: Int=10): List<String> {
        val lists=languages.ifEmpty {setOf("Hindi","English")}.mapNotNull {established[it]}
        val interleaved=(0 until (lists.maxOfOrNull {it.size} ?: 0)).flatMap {i->lists.mapNotNull {it.getOrNull(i)}}
        return (preferred+interleaved).distinctBy {key(it)}.take(limit)
    }
    fun rank(profiles: List<Artist>,catalog: List<Track>,activity: TasteActivity,prefs: Preferences,saved: Set<String> = emptySet(),now: Long=System.currentTimeMillis()): List<Artist> {
        val byId=catalog.associateBy {it.id};val weights=hashMapOf<String,Double>()
        fun add(id: String,w: Double){byId[id]?.let {val k=key(it.artist);weights[k]=(weights[k] ?: 0.0)+w}}
        activity.history.forEach {h->val w=when {h.wasSkipped && h.completionPercent<25 -> -2.0;h.wasSkipped -> -.5;h.completionPercent>=80 -> 1.8;h.completionPercent>=50 -> .6;else -> 0.0};add(h.trackId,w*exp(-(now-h.playedAt).coerceAtLeast(0)/86400000.0/45))}
        activity.likes.forEach {add(it,3.5)}
        val latest=activity.feedback.sortedByDescending {it.timestamp}.distinctBy {it.trackId}
        val suppressed=latest.filter {it.feedbackType=="LESS_OF_ARTIST"}.map {key(it.artistName)}.toSet()
        latest.filter {it.feedbackType=="MORE_LIKE_THIS"}.forEach {add(it.trackId,2.0)}
        val preferred=prefs.artists.map {key(it)}.toSet()
        val localStars=seeds(prefs.languages,limit=100).map {key(it)}.toSet()
        val allStars=established.values.flatten().map {key(it)}.toSet()
        return profiles.filter {it.id.startsWith("UC") && it.name.isNotBlank() && key(it.name) !in suppressed}.distinctBy {key(it.name)}.mapNotNull {a->
            val k=key(a.name);val affinity=weights[k] ?: 0.0;val explicit=k in preferred || a.id in saved
            if(!explicit && affinity < -1.0)return@mapNotNull null
            if(!explicit && affinity<=0 && k !in localStars && k !in allStars)return@mapNotNull null
            a to ((if(affinity>0)ln(1+affinity)*5 else affinity)+(if(explicit)8.0 else 0.0)+(if(k in localStars)3.0 else if(k in allStars)1.0 else 0.0)+(if(a.avatarUrl.isNotBlank()).2 else 0.0))
        }.sortedWith(compareByDescending<Pair<Artist,Double>> {it.second}.thenBy {it.first.name}).map {it.first}.take(48)
    }
}
