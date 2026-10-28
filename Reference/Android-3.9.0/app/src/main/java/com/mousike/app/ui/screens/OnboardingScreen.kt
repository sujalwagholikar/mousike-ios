package com.mousike.app.ui.screens
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mousike.app.ui.components.*
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.MousikeViewModel

/** Direct page changes. No staged intro, countdown or completion animation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun OnboardingScreen(vm: MousikeViewModel) {
 val prefs by vm.preferences.collectAsStateWithLifecycle()
 var page by rememberSaveable {mutableIntStateOf(0)}
 var name by rememberSaveable {mutableStateOf(prefs.name.takeUnless {it=="Listener"}.orEmpty())}
 var languages by rememberSaveable {mutableStateOf(prefs.languages)}
 var genres by rememberSaveable {mutableStateOf(prefs.genres)}
 var artists by rememberSaveable {mutableStateOf(prefs.artists)}
 var query by rememberSaveable {mutableStateOf("")}
 val options by vm.onboardingArtists.collectAsStateWithLifecycle()
 val loading by vm.onboardingArtistsLoading.collectAsStateWithLifecycle()
 val error by vm.onboardingArtistsError.collectAsStateWithLifecycle()
 LaunchedEffect(page,query,languages){if(page==2)vm.discoverOnboardingArtists(languages,query)}
 BackHandler(enabled=page>0){page--}
 Column(Modifier.fillMaxSize().background(ObsidianDark).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
  Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
   if(page>0)IconButton({page--}){Icon(Icons.Default.ArrowBack,"Previous step",tint=TextPrimary)} else MousikeLogo(Modifier.size(34.dp))
   Spacer(Modifier.width(10.dp));Text("Mousiké",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=19.sp,color=TextPrimary)
   Spacer(Modifier.weight(1f));TextButton({vm.completeOnboarding(name,languages,genres,artists)},Modifier.testTag("onboarding_skip")){Text("Do this later",color=TextSecondary,fontSize=12.sp)}
  }
  Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){repeat(3){i->Box(Modifier.weight(1f).height(3.dp).background(if(i<=page)MousikeGreen else SurfaceElevated,CircleShape))}}
  if(page==2) {
   Column(Modifier.padding(horizontal=24.dp)) {
    OnboardingEyebrow("THE PEOPLE BEHIND YOUR SOUND")
    Text("Who’s on\nyour repeat?",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=35.sp,lineHeight=39.sp,color=TextPrimary,letterSpacing=(-1).sp)
    Text("Pick a few favourites. We’ll find the connections.",color=TextSecondary,fontSize=13.sp,modifier=Modifier.padding(top=10.dp,bottom=16.dp))
    OutlinedTextField(query,{query=it.take(100)},Modifier.fillMaxWidth().testTag("onboarding_artist_search"),singleLine=true,placeholder={Text("Search any artist",fontSize=13.sp)},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(18.dp))
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp)){Text(if(artists.isEmpty())"Your favourites start here" else "${artists.size} favourites selected",color=TextSecondary,fontSize=12.sp,modifier=Modifier.weight(1f));Text("YouTube Music",color=TextMuted,fontSize=10.sp)}
   }
   if(options.isEmpty())Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
    Icon(if(error==null)Icons.Default.People else Icons.Default.WifiOff,null,tint=MousikeGreen,modifier=Modifier.size(44.dp))
    Text(if(loading)"Finding your artists…" else "Your artists are one search away",fontFamily=Manrope,fontWeight=FontWeight.Bold,color=TextPrimary,fontSize=19.sp,modifier=Modifier.padding(top=18.dp))
    Text(error ?: "Real artist profiles and photos appear here as they arrive. You can continue and choose later.",fontSize=13.sp,lineHeight=20.sp,color=TextSecondary,modifier=Modifier.padding(top=10.dp))
    if(!loading)TextButton({vm.discoverOnboardingArtists(languages,query)}){Text("Try again")}
   } else LazyVerticalGrid(GridCells.Adaptive(96.dp),Modifier.weight(1f).fillMaxWidth().testTag("onboarding_artist_grid"),contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
    items(options,key={it.id}){artist->val selected=artist.name in artists
     Column(Modifier.fillMaxWidth().clickable {artists=if(selected)artists-artist.name else artists+artist.name}.testTag("choose_artist_${artist.id}"),horizontalAlignment=Alignment.CenterHorizontally) {
      Box(Modifier.size(90.dp)) {
       Box(Modifier.fillMaxSize().border(if(selected)3.dp else 1.dp,if(selected)MousikeGreen else SurfaceElevated,CircleShape).padding(5.dp).clip(CircleShape)){MusicArtwork(artist.avatarUrl,artist.name,Modifier.fillMaxSize(),ContentScale.Crop)}
       if(selected)Box(Modifier.align(Alignment.BottomEnd).size(26.dp).background(MousikeGreen,CircleShape).border(3.dp,ObsidianDark,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Default.Check,null,tint=Color.Black,modifier=Modifier.size(15.dp))}
      }
      Text(artist.name,color=if(selected)TextPrimary else TextSecondary,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=9.dp))
     }
    }
   }
  } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
   Spacer(Modifier.height(20.dp));OnboardingEyebrow(if(page==0)"SOUND IN MOTION" else "A LITTLE MORE LIKE YOU")
   Text(if(page==0)"Make room\nfor your sound." else "Find your\nfrequency.",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=38.sp,lineHeight=43.sp,letterSpacing=(-1.2).sp,color=TextPrimary)
   Text(if(page==0)"Your favourites. Your discoveries. A music space that feels like yours." else "Choose the languages and musical worlds you love. Your listening will do the rest.",color=TextSecondary,fontSize=14.sp,lineHeight=22.sp,modifier=Modifier.padding(top=16.dp,bottom=26.dp))
   if(page==0) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(MousikeGreen.copy(alpha=.22f),SurfaceCard))).padding(22.dp),verticalAlignment=Alignment.CenterVertically) {
     MousikeLogo(Modifier.size(66.dp));Spacer(Modifier.width(20.dp));Column{Text("A soundtrack.\nOnly you could make.",fontFamily=Manrope,fontWeight=FontWeight.Bold,color=TextPrimary,fontSize=20.sp,lineHeight=26.sp);Text("PRIVATE • PERSONAL • YOURS",fontSize=9.sp,letterSpacing=1.sp,color=TextSecondary,modifier=Modifier.padding(top=10.dp))}
    }
    Spacer(Modifier.height(24.dp));OutlinedTextField(name,{name=it.take(40)},Modifier.fillMaxWidth().testTag("onboarding_name"),label={Text("What should we call you?")},singleLine=true,shape=RoundedCornerShape(18.dp));Spacer(Modifier.height(18.dp))
    listOf(Icons.Default.Headphones to "Listen with your screen off",Icons.Default.AutoAwesome to "Discover music that feels like you",Icons.Default.LockOpen to "Your music space. No account needed.").forEach{(icon,text)->Row(Modifier.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MousikeGreen,modifier=Modifier.size(20.dp));Spacer(Modifier.width(14.dp));Text(text,color=TextSecondary,fontSize=13.sp)}}
   } else {
    OnboardingEyebrow("LANGUAGES");FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Hindi","English","Marathi","Punjabi","Tamil","Telugu","Bengali","Korean").forEach{choice->FilterChip(choice in languages,{languages=if(choice in languages)languages-choice else languages+choice},{Text(choice)},shape=CircleShape)}}
    Spacer(Modifier.height(24.dp));OnboardingEyebrow("YOUR MUSICAL WORLDS");FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Indie","Pop","Bollywood","Hip-Hop","Lo-Fi","Electronic","Rock","Classical","Devotional","Folk","Sufi","Jazz").forEach{choice->FilterChip(choice in genres,{genres=if(choice in genres)genres-choice else genres+choice},{Text(choice)},shape=CircleShape)}}
   };Spacer(Modifier.height(22.dp))
  }
  Column(Modifier.fillMaxWidth().background(ObsidianDark).padding(horizontal=24.dp,vertical=14.dp)) {
   Button({if(page<2)page++ else vm.completeOnboarding(name,languages,genres,artists)},Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("onboarding_continue"),shape=CircleShape,colors=ButtonDefaults.buttonColors(containerColor=MousikeGreen,contentColor=Color.Black)){Text(if(page<2)"Continue" else "Make yourself at home",fontWeight=FontWeight.Bold,fontSize=15.sp);Spacer(Modifier.width(12.dp));Icon(Icons.Default.ArrowForward,null,Modifier.size(20.dp))}
   Text("Your taste stays on this device. AI is optional.",color=TextMuted,fontSize=10.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=12.dp))
  }
 }
}
@Composable private fun OnboardingEyebrow(text: String){Text(text,color=MousikeGreen,fontSize=10.sp,letterSpacing=1.7.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(bottom=12.dp))}
