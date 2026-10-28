package com.mousike.app.playback

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mousike.app.performance.ResolutionSignal
import com.mousike.app.performance.BoundedResolution
import org.json.JSONObject
import java.util.UUID

/** Interpreter starts once in the background; individual network calls remain independently cancellable. */
object PythonEngine {
    @Volatile private var application: Context?=null
    fun install(context: Context){application=context.applicationContext;Thread({runCatching {call("warmup",JSONObject().put("visitor",VisitorSession.get()))}},"python-warmup").apply {isDaemon=true;start()}}
    private fun python(): Python {
        if(!Python.isStarted())synchronized(this){if(!Python.isStarted())Python.start(AndroidPlatform(checkNotNull(application){"Python context unavailable"}))}
        return Python.getInstance()
    }
    fun call(operation: String,payload: JSONObject=JSONObject(),signal: ResolutionSignal=ResolutionSignal(),timeoutMs: Long=10000): JSONObject {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Python requests must run off the UI thread"}
        return BoundedResolution.run(timeoutMs,signal,background=operation in setOf("catalog","metadata","reset")) {
            signal.check();val token=UUID.randomUUID().toString();PythonNetwork.register(token,signal)
            try {val module=python().getModule("mousike_backend");signal.check()
                val result=module.callAttr("dispatch",operation,payload.toString(),token).toString();signal.check();JSONObject(result).also {root ->root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf {it.isNotBlank()}?.let {VisitorSession.put(it)}}
            } finally {PythonNetwork.unregister(token)}
        }
    }
}
