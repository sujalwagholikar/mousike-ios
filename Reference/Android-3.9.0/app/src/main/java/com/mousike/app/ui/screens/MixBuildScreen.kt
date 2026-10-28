package com.mousike.app.ui.screens
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mousike.app.ui.components.MousikeLogo
import com.mousike.app.ui.theme.*
import com.mousike.app.ui.viewmodel.MousikeViewModel
/** Immediate assembly; no countdown, numbered stages or automatic playback. */
@Composable fun MixBuildScreen(vm: MousikeViewModel){
 val title by vm.mixTitle.collectAsStateWithLifecycle();val error by vm.mixError.collectAsStateWithLifecycle()
 BackHandler{vm.navigateBack()}
 Column(Modifier.fillMaxSize().background(ObsidianDark).padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
  MousikeLogo(Modifier.size(64.dp));Spacer(Modifier.height(24.dp));Text(title,style=MaterialTheme.typography.headlineMedium,color=TextPrimary)
  Text(error ?: "Preparing your track list…",color=TextSecondary,modifier=Modifier.padding(top=12.dp,bottom=24.dp))
  if(error!=null)Button({vm.retryDailyMix()}){Text("Try again")};TextButton({vm.navigateBack()}){Text("Back to your music")}
 }
}
