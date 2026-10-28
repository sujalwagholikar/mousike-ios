package com.mousike.app.ui.personalization

import com.mousike.app.data.model.Track
import com.mousike.app.recommendation.TasteRanker

/** Search selects one song; search result order is never its listening queue. */
object SearchContinuation {
    private val album=Regex("(?i)full album|jukebox|compilation")
    fun queue(selected: Track,taste: List<Track>,results: List<Track>,recentIds: Set<String> = emptySet(),limit: Int=24): List<Track> {
        val searchIds=results.map {it.id}.toSet()
        val searchSongs=results.map {TasteRanker.canonical(it)}.toSet()
        val candidates=taste.filter {t->t.id!=selected.id && t.videoId!=selected.videoId && t.id !in searchIds && TasteRanker.canonical(t) !in searchSongs && TasteRanker.canonical(t)!=TasteRanker.canonical(selected) && (t.endMs!=null || t.durationSec<=480 && !album.containsMatchIn(t.title))}.distinctBy {TasteRanker.canonical(it)}.toMutableList()
        val out=mutableListOf(selected);val counts=mutableMapOf(selected.artist.lowercase() to 1)
        while(candidates.isNotEmpty() && out.size<limit.coerceIn(1,40)) {
            val pick=candidates.indices.maxBy {i->-i*.16-(counts[candidates[i].artist.lowercase()] ?: 0)*1.3-(if(candidates[i].artist.equals(out.last().artist,true))2.5 else 0.0)-(if(candidates[i].id in recentIds)3.0 else 0.0)}
            val t=candidates.removeAt(pick);out.add(t);val key=t.artist.lowercase();counts[key]=(counts[key] ?: 0)+1
        }
        return out
    }
}
