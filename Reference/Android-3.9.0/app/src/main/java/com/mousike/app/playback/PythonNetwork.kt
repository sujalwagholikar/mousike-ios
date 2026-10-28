package com.mousike.app.playback

import com.mousike.app.performance.ResolutionSignal
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One pooled Android transport shared by the persistent Python API. No shell or subprocess. */
object PythonNetwork {
    private val signals=ConcurrentHashMap<String,ResolutionSignal>()
    private val http=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS)
        .callTimeout(8,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun register(token: String,signal: ResolutionSignal){signals[token]=signal}
    fun unregister(token: String){signals.remove(token)}
    @JvmStatic fun request(token: String,url: String,headersJson: String,body: String): String {
        val signal=signals[token] ?: throw InterruptedIOException("Python request expired")
        signal.check()
        val uri=android.net.Uri.parse(url)
        require(uri.scheme=="https" && uri.host in setOf("www.youtube.com","music.youtube.com") && uri.path.orEmpty().startsWith("/youtubei/v1/")) {"Unsupported API URL"}
        val builder=Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        val headers=JSONObject(headersJson)
        // Let OkHttp negotiate/decompress gzip; forwarding HTTPX Accept-Encoding would disable that.
        for(key in headers.keys())if(key.lowercase() !in setOf("host","content-length","connection","accept-encoding"))builder.header(key,headers.getString(key))
        val call=http.newCall(builder.build());signal.onCancel {call.cancel()};signal.check()
        return call.execute().use {r ->signal.check();val source=r.body?.source()
            require(source==null || !source.request(4L*1024*1024+1)){"API response too large"}
            val text=source?.readUtf8() ?: "{}";signal.check()
            "${r.code}\n$text"
        }
    }
}
