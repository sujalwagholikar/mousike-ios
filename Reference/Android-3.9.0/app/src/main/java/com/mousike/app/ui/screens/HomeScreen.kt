package com.mousike.app.ui.screens
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mousike.app.data.catalog.MusicCatalog
import com.mousike.app.data.model.*
import com.mousike.app.ui.components.*
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(viewModel: MousikeViewModel,modifier: Modifier=Modifier){
 val revision by viewModel.catalogVersion.collectAsStateWithLifecycle()
 val state by viewModel.browsingPlaybackState.collectAsStateWithLifecycle()
 val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
 val feed by viewModel.homeFeed.collectAsStateWithLifecycle()
 val liked by viewModel.likedTrackIds.collectAsStateWithLifecycle()
 val artists by viewModel.recommendedArtists.collectAsStateWithLifecycle()
 val artistLoading by viewModel.artistDiscoveryLoading.collectAsStateWithLifecycle()
 val artistError by viewModel.artistDiscoveryError.collectAsStateWithLifecycle()
 var tastePanel by rememberSaveable{mutableStateOf(false)}
 val recent by viewModel.recentlyPlayed.collectAsStateWithLifecycle()
 val status by viewModel.catalogStatus.collectAsStateWithLifecycle()
 val appearance=LocalAppearance.current
 var filter by rememberSaveable{mutableStateOf("For you")}
 val songs=remember(recommendations.madeForYou,feed.songs,revision){(recommendations.madeForYou+feed.songs+feed.discoveries+recommendations.discoverWeekly+MusicCatalog.TRACKS).distinctBy{it.id}.take(72)}
 val discoveries=feed.discoveries.ifEmpty{recommendations.discoverWeekly}
 val albums=remember(feed.albums,revision){(listOfNotNull(MusicCatalog.getAlbumById(com.mousike.app.ui.personalization.TasteAlbumBuilder.ALBUM_ID))+feed.albums.ifEmpty{MusicCatalog.ALBUMS.take(96)}).distinctBy{it.id}.take(96)}
 val quick=remember(recent,songs){(recent+songs).distinctBy{it.id}.take(6)}
 val mixes by produceState<Map<String,List<Track>>>(emptyMap(),recommendations,revision){value=withContext(Dispatchers.Default){PersonalMixes.associate{it.id to personalMixTracks(it,recommendations,MusicCatalog.TRACKS)}}}
 val loading=songs.isEmpty() && status.startsWith("Loading")
 LaunchedEffect(Unit){viewModel.expandHome()}
 LaunchedEffect(filter){if(filter=="Artists")viewModel.discoverRecommendedArtists()}
 if(tastePanel)ModalBottomSheet(onDismissRequest={tastePanel=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=ObsidianDark,contentColor=TextPrimary){
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=24.dp)){
   Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){Text("Your taste studio",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=25.sp,color=TextPrimary);Text("A collection that gets to know you.",fontSize=12.sp,color=TextSecondary,modifier=Modifier.padding(top=6.dp))}
    IconButton({tastePanel=false}){Icon(Icons.Default.Close,"Close taste studio")}
   }
   Spacer(Modifier.height(20.dp))
   TasteHeroCard(appearance.name,songs,liked.size){tastePanel=false;viewModel.buildMyTaste()}
   Text("Completed listens, favourites and early skips shape your album. Build it, explore the song list, then press play whenever you’re ready.",color=TextSecondary,fontSize=12.sp,lineHeight=19.sp,modifier=Modifier.padding(horizontal=24.dp,vertical=18.dp))
   val savedTaste=MusicCatalog.getAlbumById(com.mousike.app.ui.personalization.TasteAlbumBuilder.ALBUM_ID)
   if(savedTaste!=null)OutlinedButton({tastePanel=false;viewModel.openAlbum(savedTaste)},Modifier.fillMaxWidth().padding(horizontal=24.dp).heightIn(min=48.dp),shape=CircleShape){Icon(Icons.Default.Album,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Open your latest Taste album")}
  }
 }
 DailyFeatureSpotlight(enabled=true,onCustomise={viewModel.navigateTo(NavScreen.SETTINGS)})
 LazyColumn(modifier.fillMaxSize().background(ObsidianDark).testTag("home_list"),contentPadding=PaddingValues(bottom=30.dp)){
  item("greeting"){
   Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=20.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){Text(viewModel.getDynamicGreeting(),color=TextSecondary,fontSize=12.sp);Text("For you.",color=TextPrimary,fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=34.sp,letterSpacing=(-1).sp,modifier=Modifier.padding(top=4.dp))}
    IconButton({viewModel.refreshYouTubeMusic()},Modifier.testTag("refresh_home_btn")){Icon(Icons.Default.Refresh,"Refresh your music",tint=TextSecondary)}
    Box(Modifier.size(44.dp).clip(CircleShape).background(MousikeGreen.copy(alpha=.14f)).clickable{viewModel.navigateTo(NavScreen.SETTINGS)}.testTag("home_settings_btn"),contentAlignment=Alignment.Center){Text(appearance.name.take(1).uppercase(),fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=19.sp,color=MousikeGreen)}
   }
   LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    item("taste_studio_entry"){AssistChip({tastePanel=true},{Text("Your taste",fontSize=12.sp)},leadingIcon={Icon(Icons.Default.AutoAwesome,null,Modifier.size(16.dp),tint=MousikeGreen)},shape=CircleShape,modifier=Modifier.testTag("taste_studio_entry"))}
    items(listOf("For you","Mixes","Albums","Artists")){option->FilterChip(filter==option,{filter=option},{Text(option,fontSize=12.sp)},shape=CircleShape)}
   };Spacer(Modifier.height(18.dp))
  }
  if(filter=="For you" && quick.isNotEmpty())item("quick"){
   SectionHeader(if(recent.isEmpty())"Your quick picks" else "Jump back in","A familiar place to start")
   Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    quick.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{t->QuickPickTile(t,state.currentTrack?.id==t.id && state.isPlaying,{viewModel.playHomeTrack(t)},Modifier.weight(1f))};if(row.size==1)Spacer(Modifier.weight(1f))}}
   };Spacer(Modifier.height(20.dp))
  }
  if(loading){items(3){HomeSkeletonSection()}}
  if(!loading && songs.isEmpty())item("empty"){
   Column(Modifier.fillMaxWidth().padding(24.dp).background(SurfaceCard,RoundedCornerShape(22.dp)).padding(22.dp)){
    Text("Your next favourite is out there.",fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=22.sp,color=TextPrimary)
    Text("Search for an artist to start your collection, or refresh your music.",color=TextSecondary,fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(top=10.dp))
    Row{TextButton({viewModel.navigateTo(NavScreen.SEARCH)}){Text("Find music")};TextButton({viewModel.refreshYouTubeMusic()}){Text("Refresh")}}
   }
  }
  if(filter=="For you" && songs.isNotEmpty())item("songs"){
   SectionHeader("Made for your day","Your favourites, connected to something new")
   SongShelf(songs){viewModel.playHomeTrack(it)};Spacer(Modifier.height(22.dp))
  }
  if(appearance.showMixes && filter in listOf("For you","Mixes"))item("mixes"){
   SectionHeader("Your daily soundtrack","Collections that follow your listening")
   LazyRow(Modifier.testTag("mix_shelf"),contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
    items(PersonalMixes,key={it.id}){mix->PersonalMixCard(mix,mixes[mix.id].orEmpty()){viewModel.buildPersonalMix(mix)}}
   };Spacer(Modifier.height(22.dp))
  }
  if(filter=="For you" && discoveries.isNotEmpty())item("discover"){
   SectionHeader("A little outside your orbit","New connections. Still your kind of music.")
   SongShelf(discoveries){viewModel.playHomeTrack(it)};Spacer(Modifier.height(22.dp))
  }
  if(appearance.showAlbums && filter in listOf("For you","Albums")){
   albums.chunked(24).take(4).forEachIndexed{shelf,releases->item("albums_$shelf"){
    SectionHeader(listOf("Albums in your orbit","From your musical world","A little deeper","Keep exploring")[shelf],if(appearance.artists.isEmpty())"Releases shaped by your listening" else "Connected to ${appearance.artists.take(2).joinToString(" & ")}")
    LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
     items(releases,key={it.id}){album->Column(Modifier.width(164.dp).clickable{viewModel.openAlbum(album)}){
      if(album.id==com.mousike.app.ui.personalization.TasteAlbumBuilder.ALBUM_ID)TasteAlbumCover(album.trackIds.mapNotNull{MusicCatalog.getTrackById(it)},Modifier.size(164.dp))
      else MusicArtwork(album.coverUrl,album.title,Modifier.size(164.dp).clip(ArtworkShape))
      Text(album.title,color=TextPrimary,fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=13.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp))
      Text(album.artist,color=TextMuted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
     }}
    };Spacer(Modifier.height(24.dp))
   }}
  }
  if(appearance.showArtists && filter in listOf("For you","Artists") && artists.isNotEmpty())item("artists"){
   SectionHeader("Artists worth your time","Your most listened, favourites and established voices")
   LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){
    items(artists,key={it.id}){artist->Column(Modifier.width(112.dp).clickable{viewModel.openArtist(artist)},horizontalAlignment=Alignment.CenterHorizontally){
     MusicArtwork(artist.avatarUrl,artist.name,Modifier.size(112.dp).clip(CircleShape),ContentScale.Crop)
     Text(artist.name,color=TextPrimary,fontWeight=FontWeight.SemiBold,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=10.dp))
    }}
   };Spacer(Modifier.height(24.dp))
  }
  if(appearance.showArtists && filter=="For you" && artists.isEmpty())item("find_artists"){
   Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceCard).clickable{filter="Artists"}.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.People,null,tint=MousikeGreen);Column(Modifier.weight(1f).padding(horizontal=14.dp)){Text("Find your artists",fontFamily=Manrope,fontWeight=FontWeight.Bold,color=TextPrimary,fontSize=15.sp);Text("Familiar favourites and established voices",color=TextSecondary,fontSize=11.sp,modifier=Modifier.padding(top=4.dp))};Icon(Icons.Default.ChevronRight,null,tint=TextMuted)}
  }
  if(filter=="Artists" && artists.isEmpty())item("artist_empty"){
   Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
    Icon(Icons.Default.PersonSearch,null,Modifier.size(32.dp),tint=MousikeGreen)
    Text(if(artistLoading)"Finding familiar voices" else "Your next favourite voice",fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=20.sp,color=TextPrimary,modifier=Modifier.padding(top=14.dp))
    Text(artistError ?: "Established artists and the people you listen to most will appear here. Search is always available to discover someone new.",fontSize=12.sp,color=TextSecondary,lineHeight=19.sp,modifier=Modifier.padding(vertical=12.dp))
    if(!artistLoading)TextButton({viewModel.discoverRecommendedArtists(force=true)}){Text("Try again")}
    TextButton({viewModel.navigateTo(NavScreen.SEARCH)}){Text("Search for an artist")}
   }
  }
  if(filter=="For you" && recommendations.heavyRotation.isNotEmpty()){
   item{SectionHeader("Still on repeat","Some songs just stay with you")}
   items(recommendations.heavyRotation.take(8),key={"rotation_${it.id}"}){track->TrackItem(track,state.currentTrack?.id==track.id,state.isPlaying,liked.contains(track.id),{viewModel.playHomeTrack(track)},{viewModel.toggleLikeTrack(track.id)},{viewModel.addToQueue(track)},{viewModel.setTrackToAddToPlaylist(track)},{viewModel.setFeedbackDialogForTrack(track)},Modifier.padding(horizontal=12.dp))}
  }
 }
}
@Composable private fun SongShelf(tracks: List<Track>,onPlay: (Track)->Unit){
 LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
  items(tracks,key={it.id}){t->Column(Modifier.width(164.dp).clickable{onPlay(t)}){
   Box(Modifier.size(164.dp).clip(ArtworkShape).background(SurfaceCard)){
    MusicArtwork(t.coverUrl,t.title,Modifier.fillMaxSize())
    FilledIconButton({onPlay(t)},Modifier.align(Alignment.BottomEnd).padding(9.dp).size(36.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=MousikeGreen,contentColor=ObsidianDark)){Icon(MousikeIcons.Play,"Play ${t.title}",Modifier.size(23.dp))}
   }
   Text(t.title,color=TextPrimary,fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=13.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp))
   Text(t.artist,color=TextMuted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
  }}
 }
}
@Composable fun QuickPickTile(track: Track,isPlaying: Boolean,onClick: ()->Unit,modifier: Modifier=Modifier){
 Row(modifier.heightIn(min=60.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceCard).clickable(onClick=onClick),verticalAlignment=Alignment.CenterVertically){
  MusicArtwork(track.coverUrl,track.title,Modifier.size(56.dp),ContentScale.Fit)
  Text(track.title,color=if(isPlaying)MousikeGreen else TextPrimary,fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).padding(horizontal=10.dp))
 }
}
@Composable fun SectionHeader(title: String,subtitle: String,modifier: Modifier=Modifier){Column(modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)){
 Text(title,color=TextPrimary,fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=21.sp,letterSpacing=(-.4).sp)
 Text(subtitle,color=TextMuted,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=5.dp,bottom=4.dp))
}}
