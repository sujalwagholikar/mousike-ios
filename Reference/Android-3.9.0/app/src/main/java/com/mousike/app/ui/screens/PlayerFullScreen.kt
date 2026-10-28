package com.mousike.app.ui.screens
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mousike.app.data.model.PlaybackState
import com.mousike.app.data.model.RepeatMode
import com.mousike.app.settings.Preferences
import com.mousike.app.ui.components.*
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.*

enum class PlayerAction{COLLAPSE,PLAY_PAUSE,NEXT,PREVIOUS,SHUFFLE,REPEAT,LIKE,DOWNLOAD,QUEUE,LYRICS,EQUALIZER,SLEEP,PLAYLIST,FEEDBACK,CUSTOMISE}
private data class CoverIdentity(val id: String,val url: String,val title: String)

@Composable fun PlayerFullScreen(viewModel: MousikeViewModel,modifier: Modifier=Modifier){
 BackHandler{viewModel.navigateBack()}
 val state by viewModel.browsingPlaybackState.collectAsStateWithLifecycle()
 val preferences by viewModel.preferences.collectAsStateWithLifecycle()
 val liked by viewModel.likedTrackIds.collectAsStateWithLifecycle()
 val downloads by viewModel.downloads.collectAsStateWithLifecycle()
 val track=state.currentTrack
 val output=rememberAudioOutput()
 PlayerSystemBars(preferences.playerBackdrop)
 val context=LocalContext.current
 var showOutput by remember{mutableStateOf(false)}
 if(showOutput)AlertDialog(onDismissRequest={showOutput=false},icon={Icon(outputIcon(output.kind),null,tint=MousikeGreen)},title={Text(output.name,fontFamily=Manrope,fontWeight=FontWeight.Bold)},text={Column{Text(output.detail,color=TextSecondary);Text("Manage connected speakers or headphones in Android’s sound and Bluetooth settings.",fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(top=12.dp))}},confirmButton={TextButton({showOutput=false;runCatching {context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))}}){Text("Sound settings")}},dismissButton={TextButton({showOutput=false}){Text("Done")}})
 if(track==null){Column(modifier.fillMaxSize().background(ObsidianDark).windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){MousikeLogo(Modifier.size(72.dp));Text("Your soundtrack starts here.",style=MaterialTheme.typography.headlineSmall,color=TextPrimary,modifier=Modifier.padding(vertical=24.dp));Button({viewModel.navigateTo(NavScreen.SEARCH)}){Text("Find music")}};return}
 val ambient=when(preferences.playerBackground){"Solid"->ObsidianDark;"Accent"->MousikeGreen;else->coverAmbient(track.coverUrl)}
 val download=downloads.firstOrNull{it.track.id==track.id}
 PlayerChrome(state,track.id in liked,download?.state.orEmpty(),preferences,ambient,{action->when(action){
  PlayerAction.COLLAPSE->viewModel.navigateBack()
  PlayerAction.PLAY_PAUSE->viewModel.togglePlayPause()
  PlayerAction.NEXT->viewModel.playNext()
  PlayerAction.PREVIOUS->viewModel.playPrevious()
  PlayerAction.SHUFFLE->viewModel.toggleShuffle()
  PlayerAction.REPEAT->viewModel.cycleRepeatMode()
  PlayerAction.LIKE->viewModel.toggleLikeTrack(track.id)
  PlayerAction.DOWNLOAD->viewModel.download(track)
  PlayerAction.QUEUE->viewModel.openQueue()
  PlayerAction.LYRICS->viewModel.openLyrics()
  PlayerAction.EQUALIZER->viewModel.setEqualizerDialogVisible(true)
  PlayerAction.SLEEP->viewModel.setSleepTimerDialogVisible(true)
  PlayerAction.PLAYLIST->viewModel.setTrackToAddToPlaylist(track)
  PlayerAction.FEEDBACK->viewModel.setFeedbackDialogForTrack(track)
  PlayerAction.CUSTOMISE->viewModel.navigateTo(NavScreen.SETTINGS)
 }},modifier,output,{showOutput=true}){ConnectedPlayerTimeline(viewModel,preferences.reduceMotion)}
}

@Composable fun PlayerChrome(state: PlaybackState,liked: Boolean,downloadState: String,preferences: Preferences,ambient: Color,onAction: (PlayerAction)->Unit,modifier: Modifier=Modifier,output: AudioOutput=AudioOutput("This device",AudioOutputKind.PHONE,"Device audio"),onOutput: ()->Unit={},timeline: @Composable ()->Unit){
 val palette=LocalPalette.current
 val ink=remember(palette,preferences.playerBackdrop){if(preferences.playerBackdrop)palette.copy(background=Color(0xFF080A0D),surface=Color(0xFF11151B),card=Color(0xFF1A2029),elevated=Color(0xFF262D38),text=Color.White,secondary=Color(0xFFDCE2EC),muted=Color(0xFFD0D7E2)) else palette}
 val inherited=MaterialTheme.colorScheme
 val scheme=remember(inherited,ink,preferences.playerBackdrop){if(preferences.playerBackdrop)inherited.copy(background=ink.background,surface=ink.surface,onBackground=ink.text,onSurface=ink.text,surfaceVariant=ink.card,onSurfaceVariant=ink.secondary,primary=ink.accent,onPrimary=Color(0xFF15171C)) else inherited}
 CompositionLocalProvider(LocalPalette provides ink){MaterialTheme(colorScheme=scheme){PlayerSurface(state,liked,downloadState,preferences,ambient,onAction,modifier,output,onOutput,timeline)}}
}

/** Presentation only. Controls dispatch the same existing ViewModel commands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PlayerSurface(state: PlaybackState,liked: Boolean,downloadState: String,preferences: Preferences,ambient: Color,onAction: (PlayerAction)->Unit,modifier: Modifier=Modifier,output: AudioOutput=AudioOutput("This device",AudioOutputKind.PHONE,"Device audio"),onOutput: ()->Unit={},timeline: @Composable ()->Unit){
 val track=state.currentTrack ?: return
 val currentAction by rememberUpdatedState(onAction)
 val reduce=preferences.reduceMotion
 val tint=animateColorAsState(ambient,tween(if(reduce)0 else 600),label="player_ambient_color")
 val base=ObsidianDark
 val artShape=ArtworkShape
 var menu by remember{mutableStateOf(false)}
 val coverScale=animateFloatAsState(if(state.isPlaying)1f else .975f,tween(if(reduce)0 else 380),label="cover_presence")
 val likeScale=animateFloatAsState(if(liked)1.07f else 1f,tween(if(reduce)0 else 180),label="like_feedback")
 val next=state.queue.getOrNull(state.queueIndex+1)
 val busyDownload=downloadState in listOf("Downloading","Queued")
 val small=preferences.playerLayout=="Compact"
 val fontScale=LocalDensity.current.fontScale
 BoxWithConstraints(modifier.fillMaxSize().drawBehind{drawRect(Brush.verticalGradient(listOf(tint.value.copy(alpha=.26f),base,base)))}.testTag("full_player_screen")){
  if(preferences.playerBackdrop)PlayerArtworkBackdrop(track,preferences,Modifier.matchParentSize())
  val wide=maxWidth>=720.dp
  val phonePadding=if(maxWidth<360.dp)20.dp else 26.dp
  val artFactor=when(preferences.playerArtworkSize){"Small"->.82f;"Large"->1.12f;else->1f}
  val artSize=if(wide)minOf(maxWidth*.43f,maxHeight-100.dp).coerceAtLeast(100.dp) else minOf(maxWidth-56.dp,maxHeight*(if(small || fontScale>1.2f).31f else if(preferences.playerLayout=="Immersive").45f else .39f)*artFactor,(maxHeight-540.dp-((fontScale-1f).coerceAtLeast(0f)*160f).dp).coerceAtLeast(112.dp)).coerceIn(112.dp,380.dp)
  val cover: @Composable ()->Unit={
   AnimatedContent(CoverIdentity(track.id,track.coverUrl,track.title),transitionSpec={fadeIn(tween(if(reduce)0 else 320))+scaleIn(tween(if(reduce)0 else 350),initialScale=.96f) togetherWith fadeOut(tween(if(reduce)0 else 140))},label="player_artwork_transition"){art->
    Box(Modifier.size(artSize).graphicsLayer{scaleX=coverScale.value;scaleY=coverScale.value}.shadow(if(small)10.dp else 24.dp,artShape).clip(artShape).background(SurfaceCard)){
     MusicArtwork(art.url,art.title,Modifier.fillMaxSize(),ContentScale.Fit)
    }
   }
  }
  val controls: @Composable ()->Unit={
   Row(Modifier.fillMaxWidth().padding(top=22.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){
     Text(track.title,fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=if(small)23.sp else 27.sp,lineHeight=33.sp,letterSpacing=(-.7).sp,color=TextPrimary,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.basicMarquee(iterations=if(reduce)0 else 3,initialDelayMillis=2000))
     Text(track.artist,color=TextSecondary,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
     if(preferences.showPlayerContext && track.album.isNotBlank())Text(track.album,color=TextMuted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
    }
    IconButton({onAction(PlayerAction.LIKE)},Modifier.size(48.dp).graphicsLayer{scaleX=likeScale.value;scaleY=likeScale.value}.testTag("player_like")){Icon(if(liked)MousikeIcons.HeartFilled else MousikeIcons.Heart,if(liked)"Unlike" else "Like",tint=if(liked)MousikeGreen else TextSecondary,modifier=Modifier.size(26.dp))}
   }
   Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){
    Row(Modifier.background(SurfaceCard.copy(alpha=.7f),CircleShape).padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Icon(MousikeIcons.Headphones,null,Modifier.size(12.dp),tint=MousikeGreen);Spacer(Modifier.width(6.dp));Text(preferences.audioQuality,color=TextSecondary,fontSize=10.sp)}
    Spacer(Modifier.weight(1f))
    TextButton({onAction(PlayerAction.DOWNLOAD)},enabled=!busyDownload,contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){
     Icon(if(downloadState=="Complete")Icons.Default.DownloadDone else MousikeIcons.Download,null,Modifier.size(16.dp),tint=if(downloadState=="Complete")MousikeGreen else TextSecondary)
     Spacer(Modifier.width(5.dp));Text(when(downloadState){"Complete"->"Saved offline";"Downloading","Queued"->"Saving…";else->"Save offline"},color=TextSecondary,fontSize=10.sp)
    }
   }
   timeline()
   Row(Modifier.fillMaxWidth().heightIn(min=92.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
    PlayerIcon(MousikeIcons.Shuffle,if(state.isShuffle)"Turn shuffle off" else "Turn shuffle on",state.isShuffle){onAction(PlayerAction.SHUFFLE)}
    IconButton({onAction(PlayerAction.PREVIOUS)},Modifier.size(48.dp).testTag("full_player_previous")){Icon(MousikeIcons.Previous,"Previous song",tint=TextPrimary,modifier=Modifier.size(33.dp))}
    FilledIconButton({onAction(PlayerAction.PLAY_PAUSE)},Modifier.width(86.dp).height(68.dp).testTag("full_player_play_pause"),shape=RoundedCornerShape(24.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(preferences.playerControls=="Accent")MousikeGreen else Color.White,contentColor=Color(0xFF15171C))){
     if(state.isBuffering)Box(contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(44.dp),color=Color(0xFF15171C).copy(alpha=.32f),strokeWidth=2.dp);Icon(MousikeIcons.Pause,"Pause loading",Modifier.size(29.dp))}
     else AnimatedContent(state.isPlaying || state.playWhenReady,transitionSpec={fadeIn(tween(if(reduce)0 else 150))+scaleIn(tween(if(reduce)0 else 170),initialScale=.85f) togetherWith fadeOut(tween(if(reduce)0 else 90))},label="player_transport_icon"){playing->Icon(if(playing)MousikeIcons.Pause else MousikeIcons.Play,if(playing)"Pause" else if(state.error!=null)"Retry playback" else "Play",Modifier.size(38.dp))}
    }
    IconButton({onAction(PlayerAction.NEXT)},Modifier.size(48.dp).testTag("full_player_next")){Icon(MousikeIcons.Next,"Next song",tint=TextPrimary,modifier=Modifier.size(33.dp))}
    PlayerIcon(if(state.repeatMode==RepeatMode.ONE)Icons.Default.RepeatOne else MousikeIcons.Repeat,"Change repeat mode",state.repeatMode!=RepeatMode.OFF){onAction(PlayerAction.REPEAT)}
   }
   Row(Modifier.fillMaxWidth().heightIn(min=24.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
    if(state.error!=null){Text("This source needs another try",fontSize=11.sp,color=TextSecondary);TextButton({onAction(PlayerAction.PLAY_PAUSE)}){Text("Retry",fontSize=11.sp)}}
    else if(state.isBuffering)Text(state.loadingLabel.ifBlank{"Connecting"},fontSize=11.sp,color=TextMuted)
    else Text(if(state.isPlaying)"A MOMENT, JUST FOR YOU" else "READY WHEN YOU ARE",fontSize=9.sp,letterSpacing=1.8.sp,color=TextMuted)
   }
   if(preferences.showPlayerShortcuts)Row(Modifier.fillMaxWidth().padding(top=16.dp).background(SurfaceCard.copy(alpha=.65f),RoundedCornerShape(20.dp)),horizontalArrangement=Arrangement.SpaceEvenly){
    PlayerTool(MousikeIcons.Lyrics,"Lyrics",Modifier.weight(1f)){onAction(PlayerAction.LYRICS)}
    PlayerTool(MousikeIcons.Queue,"Queue",Modifier.weight(1f)){onAction(PlayerAction.QUEUE)}
    PlayerTool(MousikeIcons.Settings,"Sound",Modifier.weight(1f)){onAction(PlayerAction.EQUALIZER)}
   }
   if(next!=null)Row(Modifier.fillMaxWidth().padding(top=18.dp).clip(RoundedCornerShape(16.dp)).clickable{onAction(PlayerAction.QUEUE)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
    MusicArtwork(next.coverUrl,next.title,Modifier.size(44.dp).clip(RoundedCornerShape(7.dp)))
    Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text("UP NEXT",fontSize=9.sp,color=MousikeGreen,letterSpacing=1.2.sp,fontWeight=FontWeight.Bold);Text(next.title,color=TextSecondary,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))}
    IconButton({onAction(PlayerAction.NEXT)}){Icon(MousikeIcons.Next,"Play next song",tint=TextSecondary,modifier=Modifier.size(22.dp))}
   }
   Spacer(Modifier.height(18.dp))
  }
  Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
   Row(Modifier.fillMaxWidth().heightIn(min=62.dp).pointerInput(Unit){var distance=0f;val threshold=56.dp.toPx();detectVerticalDragGestures(onDragStart={distance=0f},onDragEnd={if(distance>threshold)currentAction(PlayerAction.COLLAPSE)},onVerticalDrag={change,amount->distance+=amount;change.consume()})}.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
    IconButton({onAction(PlayerAction.COLLAPSE)},Modifier.testTag("full_player_collapse_btn")){Icon(MousikeIcons.Down,"Collapse player",tint=TextPrimary,modifier=Modifier.size(30.dp))}
    Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text("NOW PLAYING",fontSize=8.sp,fontWeight=FontWeight.Bold,letterSpacing=1.8.sp,color=TextSecondary,modifier=Modifier.padding(top=4.dp));CompactPlayerOutput(output,onOutput,reduce)}
    Box{IconButton({menu=true},Modifier.testTag("full_player_more_btn")){Icon(MousikeIcons.More,"Player options",tint=TextPrimary)}
     DropdownMenu(menu,{menu=false},Modifier.background(SurfaceElevated)){
      listOf(Triple(MousikeIcons.Lyrics,"Lyrics",PlayerAction.LYRICS),Triple(MousikeIcons.Queue,"Queue",PlayerAction.QUEUE),Triple(MousikeIcons.Settings,"Equalizer",PlayerAction.EQUALIZER),Triple(Icons.Default.PlaylistAdd,"Add to playlist",PlayerAction.PLAYLIST),Triple(Icons.Default.Schedule,"Sleep timer",PlayerAction.SLEEP),Triple(Icons.Default.AutoAwesome,"Tune recommendations",PlayerAction.FEEDBACK),Triple(MousikeIcons.Download,"Download song",PlayerAction.DOWNLOAD),Triple(Icons.Default.Palette,"Customise player",PlayerAction.CUSTOMISE)).forEach{(icon,label,action)->DropdownMenuItem(text={Text(label)},leadingIcon={Icon(icon,null)},onClick={menu=false;onAction(action)})}
     }
    }
   }
   if(wide)Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal=26.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.weight(1f),contentAlignment=Alignment.Center){cover()};Spacer(Modifier.width(28.dp));Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){controls()}}
   else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=phonePadding),horizontalAlignment=Alignment.CenterHorizontally){Spacer(Modifier.height(12.dp));cover();controls()}
  }
 }
}
@Composable private fun PlayerIcon(icon: ImageVector,label: String,active: Boolean,onClick: ()->Unit){IconButton(onClick,Modifier.size(48.dp)){Icon(icon,label,Modifier.size(22.dp),tint=if(active)MousikeGreen else TextMuted)}}
@Composable private fun PlayerTool(icon: ImageVector,label: String,modifier: Modifier,onClick: ()->Unit){TextButton(onClick,modifier.heightIn(min=56.dp),contentPadding=PaddingValues(4.dp)){Icon(icon,null,Modifier.size(18.dp),tint=TextSecondary);Spacer(Modifier.width(7.dp));Text(label,color=TextSecondary,fontSize=11.sp)}}
@Composable private fun ConnectedPlayerTimeline(vm: MousikeViewModel,reduce: Boolean){val live by vm.playbackState.collectAsStateWithLifecycle();PlayerTimeline(live,reduce,vm::seekTo)}
@Composable private fun PlayerTimeline(state: PlaybackState,reduce: Boolean,onSeek: (Long)->Unit){
 var dragging by remember(state.currentTrack?.id){mutableStateOf(false)};var drag by remember(state.currentTrack?.id){mutableFloatStateOf(0f)}
 val duration=state.durationMs.coerceAtLeast(1);val raw=(state.currentPositionMs.toFloat()/duration).coerceIn(0f,1f)
 val progress=if(reduce)rememberUpdatedState(raw) else animateFloatAsState(raw,tween(230,easing=LinearEasing),label="timeline_progress")
 Slider(if(dragging)drag else progress.value,{dragging=true;drag=it},modifier=Modifier.fillMaxWidth().height(42.dp).testTag("player_seek_slider"),onValueChangeFinished={onSeek((drag*duration).toLong());dragging=false},enabled=state.durationMs>0 && !state.isBuffering,colors=SliderDefaults.colors(thumbColor=MousikeGreen,activeTrackColor=MousikeGreen,inactiveTrackColor=TextPrimary.copy(alpha=.12f)))
 fun time(ms: Long)="${ms/60000}:${((ms/1000)%60).toString().padStart(2,'0')}"
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(time(if(dragging)(drag*duration).toLong() else state.currentPositionMs),color=TextMuted,fontFamily=Roboto,fontSize=10.sp);Text(if(state.durationMs>0)time(state.durationMs) else "—:—",color=TextMuted,fontFamily=Roboto,fontSize=10.sp)}
}

private fun outputIcon(kind: AudioOutputKind): ImageVector=when(kind){AudioOutputKind.PHONE->MousikeIcons.Phone;AudioOutputKind.BLUETOOTH->MousikeIcons.Bluetooth;AudioOutputKind.HEADPHONES->MousikeIcons.Headphones;AudioOutputKind.EXTERNAL->MousikeIcons.Speaker}

@Composable private fun CompactPlayerOutput(output: AudioOutput,onClick: ()->Unit,reduce: Boolean){
 Box(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClick=onClick).semantics(mergeDescendants=true){contentDescription="Audio output: ${output.name}. ${output.detail}"}.testTag("player_audio_output"),contentAlignment=Alignment.Center){
  Row(Modifier.widthIn(max=220.dp).clip(CircleShape).background(SurfaceCard.copy(alpha=.72f)).border(1.dp,TextPrimary.copy(alpha=.12f),CircleShape).padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
   Icon(outputIcon(output.kind),null,Modifier.size(13.dp),tint=MousikeGreen);Spacer(Modifier.width(6.dp))
   Crossfade(output.name,animationSpec=tween(if(reduce)0 else 180),modifier=Modifier.weight(1f,false).clearAndSetSemantics{},label="output_name"){name->Text(name,color=TextPrimary,fontSize=10.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)}
   Spacer(Modifier.width(5.dp));Icon(MousikeIcons.Down,null,Modifier.size(10.dp),tint=TextSecondary)
  }
 }
}
private tailrec fun Context.playerActivity(): Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.playerActivity();else->null}
@Composable private fun PlayerSystemBars(artwork: Boolean){
 val context=LocalContext.current
 DisposableEffect(context,artwork){
  val window=context.playerActivity()?.window
  val controller=window?.let {WindowInsetsControllerCompat(it,it.decorView)}
  val oldStatus=controller?.isAppearanceLightStatusBars
  val oldNav=controller?.isAppearanceLightNavigationBars
  if(artwork){controller?.isAppearanceLightStatusBars=false;controller?.isAppearanceLightNavigationBars=false}
  onDispose{if(oldStatus!=null)controller?.isAppearanceLightStatusBars=oldStatus;if(oldNav!=null)controller?.isAppearanceLightNavigationBars=oldNav}
 }
}
