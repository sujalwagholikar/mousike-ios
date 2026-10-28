package com.mousike.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.mousike.app.data.model.Track
import com.mousike.app.settings.Preferences

data class BackdropSpec(val source: String,val edge: Int=640) {
    val memoryKey: String get()="mousike.player.backdrop.v1:$source"
}
fun playerBackdropSpec(track: Track,p: Preferences): BackdropSpec? {
    if(!p.playerBackdrop)return null
    val source=track.coverUrl.trim().let {if(it.startsWith("//"))"https:$it" else it}
    return source.takeIf {it.isNotBlank()}?.let {BackdropSpec(it)}
}

/** One bounded decorative image. No per-frame blur, palette work, motion loop or player changes. */
@Composable fun PlayerArtworkBackdrop(track: Track,p: Preferences,modifier: Modifier=Modifier) {
    val spec=remember(track.coverUrl,p.playerBackdrop){playerBackdropSpec(track,p)} ?: return
    val context=LocalContext.current
    var lastSuccess by remember {mutableStateOf<String?>(null)}
    val previousKey=remember(spec.source){lastSuccess}
    val request=remember(spec,p.reduceMotion,context) {
        ImageRequest.Builder(context).data(spec.source).size(spec.edge,spec.edge).scale(Scale.FIT).precision(Precision.EXACT)
            .memoryCacheKey(spec.memoryKey).placeholderMemoryCacheKey(previousKey)
            .crossfade(if(p.reduceMotion)0 else 280).build()
    }
    Box(modifier.testTag("player_artwork_backdrop")) {
        AsyncImage(request,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,onSuccess={lastSuccess=spec.memoryKey})
        // Fixed scrim bounds contrast even over all-white covers. The main cover stays uncropped.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.54f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.30f),Color.Black.copy(alpha=.38f),Color.Black.copy(alpha=.68f)))))
    }
}
