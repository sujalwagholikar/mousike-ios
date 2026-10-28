package com.mousike.app.ui.personalization

import android.content.Context
import com.mousike.app.data.local.HistoryEntity
import com.mousike.app.data.local.MousikeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only presentation adapter over the existing retained history. No schema/tracking changes. */
object TasteActivityReader {
    suspend fun read(context: Context): TasteActivity = withContext(Dispatchers.IO) {
        val db=MousikeDatabase.getDatabase(context)
        val history=mutableListOf<HistoryEntity>()
        db.openHelper.readableDatabase.query("SELECT id, trackId, playedAt, completionPercent, wasSkipped FROM listening_history ORDER BY playedAt DESC LIMIT 2000").use {c->
            while(c.moveToNext())history.add(HistoryEntity(c.getLong(0),c.getString(1),c.getLong(2),c.getInt(3),c.getInt(4)!=0))
        }
        TasteActivity(db.dao().getLikedTrackIdsList().toSet(),history,db.dao().getAllFeedback())
    }
}
