package com.mousike.app.session

import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

/** Keep heard timeline entries behind the current item when native shuffle is enabled. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun forwardShuffleOrder(count: Int,current: Int,seed: Long,preferred: Int?=null): ShuffleOrder {
 val cursor=current.coerceIn(0,(count-1).coerceAtLeast(0))
 val future=(cursor+1 until count).toMutableList()
 java.util.Collections.shuffle(future,Random(seed))
 val pinned=preferred?.takeIf {it in future}
 if(pinned!=null)future.remove(pinned)
 val order=((0..cursor).toList()+listOfNotNull(pinned)+future).take(count).toIntArray()
 return ShuffleOrder.DefaultShuffleOrder(order,seed)
}
