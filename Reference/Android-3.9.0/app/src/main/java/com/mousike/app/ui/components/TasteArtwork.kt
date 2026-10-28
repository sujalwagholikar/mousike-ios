package com.mousike.app.ui.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.mousike.app.data.model.Track
import com.mousike.app.ui.theme.*

@Composable fun TasteAlbumCover(tracks: List<Track>,modifier: Modifier=Modifier){
 val art=remember(tracks){tracks.distinctBy{it.artist}.take(4)}
 val accent=MousikeGreen
 BoxWithConstraints(modifier.clip(ArtworkShape).background(Brush.linearGradient(listOf(Color(0xFF27212A),accent.copy(alpha=.72f),Color(0xFF382334))))){
  val edge=maxWidth
  Canvas(Modifier.fillMaxSize()){repeat(4){i->drawCircle(Color.White.copy(alpha=.08f),size.width*(.27f+i*.12f),Offset(size.width*.83f,size.height*.3f),style=androidx.compose.ui.graphics.drawscope.Stroke(size.width*.008f))}}
  Column(Modifier.align(Alignment.TopEnd).padding(edge*.08f).width(edge*.51f).graphicsLayer{rotationZ=9f}){
   repeat(2){row->Row{repeat(2){col->Box(Modifier.size(edge*.25f).padding(2.dp).clip(RoundedCornerShape(edge*.02f)).background(Color.White.copy(alpha=.08f))){art.getOrNull(row*2+col)?.let{MusicArtwork(it.coverUrl,it.artist,Modifier.fillMaxSize(),ContentScale.Fit)}}}}}
  }
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xFF18151E).copy(alpha=.96f)))))
  Row(Modifier.padding(edge*.07f),verticalAlignment=Alignment.CenterVertically){MousikeLogo(Modifier.size(edge*.11f));Spacer(Modifier.width(6.dp));Text("MADE FROM YOU",color=Color.White,fontSize=(edge.value*.035f).sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)}
  Column(Modifier.align(Alignment.BottomStart).padding(edge*.08f)){Text("BUILD MY\nTASTE",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=(edge.value*.15f).sp,lineHeight=(edge.value*.17f).sp,letterSpacing=(-1).sp,color=Color.White);Text("YOUR LISTENING. YOUR SIGNATURE.",color=Color.White.copy(alpha=.7f),fontSize=(edge.value*.028f).sp,letterSpacing=1.sp,modifier=Modifier.padding(top=edge*.04f))}
 }
}
@Composable fun TasteHeroCard(name: String,tracks: List<Track>,likes: Int,onBuild: ()->Unit){
 Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(MousikeGreen.copy(alpha=.16f),SurfaceCard))).clickable(onClick=onBuild).padding(20.dp).testTag("build_my_taste")){
  Row(verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){
    Text("YOUR LISTENING SIGNATURE",color=MousikeGreen,fontSize=9.sp,letterSpacing=1.1.sp,fontWeight=FontWeight.Bold)
    Text("A sound\nonly you have.",fontFamily=Manrope,fontWeight=FontWeight.ExtraBold,fontSize=27.sp,lineHeight=31.sp,letterSpacing=(-.8).sp,color=TextPrimary,modifier=Modifier.padding(top=12.dp))
    Text(if(likes>0)"$likes favourites. A world of connections." else "Your favourites, moods and discoveries. Woven together.",color=TextSecondary,fontSize=11.sp,lineHeight=17.sp,modifier=Modifier.padding(top=10.dp,end=6.dp))
   }
   Spacer(Modifier.width(14.dp));TasteAlbumCover(tracks,Modifier.width(102.dp).height(140.dp))
  }
  Row(Modifier.padding(top=18.dp).background(MousikeGreen,CircleShape).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Text("Build My Taste",color=Color.Black,fontWeight=FontWeight.Bold,fontSize=13.sp);Spacer(Modifier.width(10.dp));Icon(Icons.Default.AutoAwesome,null,tint=Color.Black,modifier=Modifier.size(17.dp))}
 }
}
@Composable fun HomeSkeletonSection(){
 val reduce=LocalAppearance.current.reduceMotion
 val pulse=if(reduce)rememberUpdatedState(.65f) else rememberInfiniteTransition(label="home_skeleton").animateFloat(.42f,.82f,infiniteRepeatable(tween(1100),RepeatMode.Reverse),label="skeleton_opacity")
 Column(Modifier.fillMaxWidth().padding(vertical=18.dp).testTag("home_skeleton")){
  Box(Modifier.padding(horizontal=20.dp).width(154.dp).height(18.dp).background(SurfaceElevated,CircleShape))
  LazyRow(Modifier.padding(top=16.dp).graphicsLayer{alpha=pulse.value},contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){
   items(4){Column(Modifier.width(148.dp)){Box(Modifier.size(148.dp).background(SurfaceElevated,ArtworkShape));Spacer(Modifier.height(12.dp));Box(Modifier.fillMaxWidth(.82f).height(12.dp).background(SurfaceElevated,CircleShape));Spacer(Modifier.height(8.dp));Box(Modifier.fillMaxWidth(.5f).height(9.dp).background(SurfaceElevated,CircleShape))}}
  }
 }
}
