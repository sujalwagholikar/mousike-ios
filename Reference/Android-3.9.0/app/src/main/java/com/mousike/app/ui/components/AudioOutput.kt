package com.mousike.app.ui.components

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

enum class AudioOutputKind { PHONE, BLUETOOTH, HEADPHONES, EXTERNAL }
data class AudioOutput(val name: String,val kind: AudioOutputKind,val detail: String)

/** Reads the selected system media route. Never sets player, routing, focus or Bluetooth state. */
@Composable fun rememberAudioOutput(): AudioOutput {
    val context=LocalContext.current.applicationContext;val lifecycle=LocalLifecycleOwner.current.lifecycle
    var output by remember {mutableStateOf(AudioOutput(Build.MODEL,AudioOutputKind.PHONE,"This device"))}
    DisposableEffect(context,lifecycle) {
        val manager=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val legacy=context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        var stopModern: (()->Unit)?=null;var observing=false
        fun refresh() {
            val modern=if(Build.VERSION.SDK_INT>=30)runCatching {ModernAudioOutput.current(context)}.getOrNull() else null
            if(modern!=null){output=modern;return}
            val route=legacy.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
            val wired=manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_ACCESSORY)}
            output=when {
                route!=null && route!=legacy.defaultRoute -> AudioOutput(route.name.toString(),if(route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH)AudioOutputKind.BLUETOOTH else AudioOutputKind.EXTERNAL,if(route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH)"Bluetooth audio" else "External audio")
                wired!=null -> AudioOutput(wired.productName.toString().ifBlank {"Wired headphones"},AudioOutputKind.HEADPHONES,if(wired.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_ACCESSORY))"USB audio" else "Wired audio")
                else -> AudioOutput(Build.MODEL,AudioOutputKind.PHONE,"This device • speaker")
            }
        }
        val devices=object: AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>){refresh()}
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>){refresh()}
        }
        val routes=object: MediaRouter.SimpleCallback() {
            override fun onRouteSelected(router: MediaRouter,type: Int,info: MediaRouter.RouteInfo){refresh()}
            override fun onRouteUnselected(router: MediaRouter,type: Int,info: MediaRouter.RouteInfo){refresh()}
            override fun onRouteChanged(router: MediaRouter,info: MediaRouter.RouteInfo){refresh()}
        }
        fun start(){if(observing)return;observing=true;manager.registerAudioDeviceCallback(devices,Handler(Looper.getMainLooper()));legacy.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO,routes,MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS);if(Build.VERSION.SDK_INT>=30)stopModern=runCatching {ModernAudioOutput.observe(context,::refresh)}.getOrNull();refresh()}
        fun stop(){if(!observing)return;observing=false;manager.unregisterAudioDeviceCallback(devices);legacy.removeCallback(routes);stopModern?.invoke();stopModern=null}
        val observer=LifecycleEventObserver {_,event->when(event){Lifecycle.Event.ON_START->start();Lifecycle.Event.ON_STOP->stop();else->Unit}}
        lifecycle.addObserver(observer);if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))start()
        onDispose{lifecycle.removeObserver(observer);stop()}
    }
    return output
}
@RequiresApi(30) private object ModernAudioOutput {
    fun current(context: Context): AudioOutput? {
        val selected=MediaRouter2.getInstance(context).systemController.selectedRoutes;if(selected.isEmpty())return null
        val kind=when(selected.first().type){MediaRoute2Info.TYPE_BUILTIN_SPEAKER->AudioOutputKind.PHONE;MediaRoute2Info.TYPE_BLUETOOTH_A2DP,MediaRoute2Info.TYPE_BLE_HEADSET,MediaRoute2Info.TYPE_HEARING_AID->AudioOutputKind.BLUETOOTH;MediaRoute2Info.TYPE_WIRED_HEADSET,MediaRoute2Info.TYPE_WIRED_HEADPHONES,MediaRoute2Info.TYPE_USB_HEADSET->AudioOutputKind.HEADPHONES;else->AudioOutputKind.EXTERNAL}
        val name=if(kind==AudioOutputKind.PHONE)Build.MODEL else selected.joinToString(" + "){it.name.toString()}.ifBlank {"External device"}
        val detail=when(kind){AudioOutputKind.PHONE->"This device • speaker";AudioOutputKind.BLUETOOTH->"Bluetooth audio";AudioOutputKind.HEADPHONES->"Headphones • wired / USB";AudioOutputKind.EXTERNAL->"External audio"}
        return AudioOutput(name,kind,detail)
    }
    fun observe(context: Context,onChange: ()->Unit): ()->Unit {
        val router=MediaRouter2.getInstance(context);val callback=object: MediaRouter2.ControllerCallback(){override fun onControllerUpdated(controller: MediaRouter2.RoutingController){onChange()}}
        val routes=object: MediaRouter2.RouteCallback(){
            override fun onRoutesAdded(routes: List<MediaRoute2Info>){onChange()}
            override fun onRoutesChanged(routes: List<MediaRoute2Info>){onChange()}
            override fun onRoutesRemoved(routes: List<MediaRoute2Info>){onChange()}
        }
        // A local router must register a route callback to receive system updates.
        // Passive live-audio discovery does not perform an active network scan.
        router.registerRouteCallback(context.mainExecutor,routes,RouteDiscoveryPreference.Builder(listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO),false).build())
        try {router.registerControllerCallback(context.mainExecutor,callback)} catch(e: Exception){router.unregisterRouteCallback(routes);throw e}
        return {router.unregisterControllerCallback(callback);router.unregisterRouteCallback(routes)}
    }
}
