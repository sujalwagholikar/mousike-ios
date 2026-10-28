package com.mousike.app.ui.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.NavScreen

data class NavItem(val screen: NavScreen,val label: String,val selectedIcon: ImageVector,val unselectedIcon: ImageVector)
@Composable fun MousikeBottomBar(currentScreen: NavScreen,onNavigate: (NavScreen)->Unit,modifier: Modifier=Modifier) {
 val p=LocalAppearance.current
 val largeText=LocalDensity.current.fontScale>1.35f
 val items=remember {listOf(NavItem(NavScreen.HOME,"Home",MousikeIcons.Home,MousikeIcons.Home),NavItem(NavScreen.SEARCH,"Search",MousikeIcons.Search,MousikeIcons.Search),NavItem(NavScreen.EXPLORE,"Explore",MousikeIcons.Discover,MousikeIcons.Discover),NavItem(NavScreen.LIBRARY,"Library",MousikeIcons.Library,MousikeIcons.Library),NavItem(NavScreen.SETTINGS,"Settings",MousikeIcons.Settings,MousikeIcons.Settings))}
 var lastRoot by rememberSaveable {mutableIntStateOf(0)}
 val index=items.indexOfFirst {it.screen==currentScreen}.let {if(currentScreen==NavScreen.ABOUT)4 else if(it>=0)it else lastRoot}
 LaunchedEffect(index){lastRoot=index}
 val accent=MousikeGreen;val card=CharcoalSurface;val activeInk=if(card.luminance()>.5f)lerp(accent,Color.Black,.42f) else accent;val shape=RoundedCornerShape(if(p.surfaceStyle=="Flat")16.dp else 28.dp)
 Box(modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp).testTag("bottom_navigation"),contentAlignment=Alignment.Center){
  BoxWithConstraints(Modifier.widthIn(max=680.dp).fillMaxWidth().shadow(16.dp,shape).clip(shape).background(Brush.verticalGradient(listOf(SurfaceCard,card))).border(1.dp,SurfaceBorder.copy(alpha=.75f),shape).padding(horizontal=8.dp).selectableGroup()){
   val cell=maxWidth/5
   val indicator by animateDpAsState(cell*index+(cell-48.dp)/2,tween(if(p.reduceMotion)0 else 280,easing=FastOutSlowInEasing),label="dock_position")
   Box(Modifier.offset(x=indicator,y=6.dp).width(48.dp).height(36.dp).background(accent.copy(alpha=.13f),RoundedCornerShape(15.dp)).border(1.dp,accent.copy(alpha=.20f),RoundedCornerShape(15.dp)))
   Column {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    items.forEachIndexed {i,item->
     val selected=i==index
     val scale=animateFloatAsState(if(selected)1.04f else .94f,tween(if(p.reduceMotion)0 else 220),label="dock_icon")
     Column(Modifier.weight(1f).heightIn(min=if(p.showNavLabels && !largeText)68.dp else 52.dp).selectable(selected,role=Role.Tab,onClick={onNavigate(item.screen)}).semantics{contentDescription=item.label}.testTag("nav_${item.label.lowercase()}").padding(top=11.dp,bottom=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
      Icon(item.selectedIcon,null,Modifier.size(25.dp).graphicsLayer{scaleX=scale.value;scaleY=scale.value},tint=if(selected)activeInk else TextSecondary)
      if(p.showNavLabels && !largeText){Spacer(Modifier.height(7.dp));Text(item.label,color=if(selected)TextPrimary else TextMuted,fontFamily=Inter,fontSize=10.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.clearAndSetSemantics{})}
     }
    }
   }
   if(p.showNavLabels && largeText)Text(items[index].label,color=TextPrimary,fontFamily=Inter,fontSize=12.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.align(Alignment.CenterHorizontally).padding(bottom=10.dp).clearAndSetSemantics{})
   }
  }
 }
}
