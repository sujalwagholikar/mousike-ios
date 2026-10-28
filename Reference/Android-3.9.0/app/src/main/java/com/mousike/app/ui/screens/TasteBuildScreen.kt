package com.mousike.app.ui.screens
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mousike.app.data.catalog.MusicCatalog
import com.mousike.app.ui.components.TasteAlbumCover
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.MousikeViewModel
@Composable fun TasteBuildScreen(vm: MousikeViewModel){
 val stage by vm.tasteStage.collectAsStateWithLifecycle();val error by vm.tasteError.collectAsStateWithLifecycle();val rec by vm.recommendations.collectAsStateWithLifecycle()
 val reduce=LocalAppearance.current.reduceMotion
 BackHandler{vm.navigateBack()}
 val reveal=animateFloatAsState(if(stage>=1)1f else .94f,tween(if(reduce)0 else 700),label="taste_cover_assembly")
 Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MousikeGreen.copy(alpha=.10f),ObsidianDark,ObsidianDark))).windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp).testTag("taste_build_screen"),horizontalAlignment=Alignment.CenterHorizontally){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton({vm.navigateBack()}){Icon(Icons.Default.Close,"Cancel album builder",tint=TextPrimary)};Spacer(Modifier.weight(1f));Text("MADE FROM YOUR LISTENING",color=MousikeGreen,fontSize=9.sp,letterSpacing=1.sp)}
  Spacer(Modifier.height(42.dp));TasteAlbumCover(rec.madeForYou.ifEmpty{MusicCatalog.TRACKS.take(4)},Modifier.size(240.dp).graphicsLayer{scaleX=reveal.value;scaleY=reveal.value;rotationZ=if(reduce)0f else (1-reveal.value)*-35f})
  Spacer(Modifier.height(38.dp))
  val message=when(stage){0->"Every listen leaves a trace.";1->"Finding what feels like you.";else->"Your taste, in focus."}
  AnimatedContent(if(error==null)message else "Let’s find your starting point.",transitionSpec={fadeIn(tween(if(reduce)0 else 280)) togetherWith fadeOut(tween(if(reduce)0 else 120))},label="taste_builder_headline"){Text(it,fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=28.sp,lineHeight=34.sp,color=TextPrimary,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
  Text(error ?: when(stage){0->"Reading your saved favourites, completed listens and early skips.";1->"Connecting artists, languages, moods and regional sounds.";else->"Balancing familiar favourites with a few new connections."},color=TextSecondary,fontSize=13.sp,lineHeight=21.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=14.dp))
  Spacer(Modifier.height(28.dp))
  if(error==null)LinearProgressIndicator(progress={(stage+1)/3f},Modifier.fillMaxWidth().height(3.dp),color=MousikeGreen,trackColor=SurfaceCard)
  else Button({vm.buildMyTaste()}){Text("Try again")}
  Text("Your album opens as a track list. You choose when to play.",color=TextMuted,fontSize=11.sp,lineHeight=17.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=22.dp))
 }
}
