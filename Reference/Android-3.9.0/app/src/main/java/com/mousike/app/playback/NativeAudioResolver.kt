package com.mousike.app.playback

import com.mousike.app.performance.ResolutionSignal
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Python InnerTube owns YouTube API requests; Kotlin retains the tested format-selection policy. */
@Suppress("UNUSED_PARAMETER")
class NativeAudioResolver(http: OkHttpClient?=null,baseUrl: String="",visitorStore: VisitorStore?=null) {
    data class Result(val stream: InnerTubeExtractor.Stream,val metadata: JSONObject,val alternate: InnerTubeExtractor.Stream?=null,val candidates: List<InnerTubeExtractor.Stream> = emptyList())
    fun resolve(id: String,quality: String,signal: ResolutionSignal,fallback: Boolean=false,fresh: Boolean=false): Result? {
        signal.check()
        val o=PythonEngine.call(if(fallback)"fallback" else "player",JSONObject().put("id",id).put("fresh",fresh),signal,11000)
        signal.check()
        val cpn=o.optString("_mousikeCpn")
        val direct=parse(id,o,quality,preferHls=false)
        val hls=parse(id,o,quality,preferHls=true)
        val result=direct?.copy(
            stream=if(direct.stream.isHls || cpn.isBlank())direct.stream else direct.stream.copy(url=direct.stream.url.toHttpUrl().newBuilder().setQueryParameter("cpn",cpn).build().toString()),
            alternate=hls?.stream?.takeIf {it.url!=direct.stream.url})
        return result?.copy(candidates=listOfNotNull(result.stream,result.alternate)+extraAudio(id,o,quality,cpn))
    }
    companion object {
        const val USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 25_6_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/25.6 Safari/605.1.15"
        fun parse(id: String,o: JSONObject,quality: String,preferHls: Boolean=false): Result? {
            if(o.optJSONObject("playabilityStatus")?.optString("status")!="OK")return null
            val details=o.optJSONObject("videoDetails") ?: return null
            if(details.optString("videoId")!=id)return null
            val data=o.optJSONObject("streamingData") ?: return null
            val formats=data.optJSONArray("adaptiveFormats")
            val audio=(0 until (formats?.length() ?: 0)).mapNotNull {formats?.optJSONObject(it)}
                .filter {isProgressiveAudio(it) && usable(it.optString("url"))}
                .sortedBy {it.optInt("bitrate")}
            val original=audio.filter {it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault",true)!=false}.ifEmpty {audio}
            val preferred=original.filter {it.optString("mimeType").startsWith("audio/mp4")}.ifEmpty {original}
            val selected=com.mousike.app.settings.AudioQualityPolicy.select(if(quality in setOf("Super high","Low","Data saver"))original else preferred,quality){it.optInt("bitrate")}
            // HLS is supported explicitly; never discard the only usable stream to force M4A.
            val hls=data.optString("hlsManifestUrl").takeIf {usable(it)}
            val url=if(preferHls && hls!=null)hls else selected?.optString("url") ?: hls ?: return null
            val isHls=url==hls
            val now=System.currentTimeMillis()
            val expires=Regex("(?:[?&]expire=|/expire/)(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
                ?: now+(data.optLong("expiresInSeconds",600).coerceIn(0,21600)*1000)
            val thumbs=details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val meta=JSONObject().put("id",id).put("title",details.optString("title"))
                .put("uploader",details.optString("author")).put("duration",details.optString("lengthSeconds").toDoubleOrNull() ?: 0)
                .put("description",details.optString("shortDescription"))
                .put("thumbnail",thumbs?.optJSONObject(thumbs.length()-1)?.optString("url").orEmpty())
            return Result(InnerTubeExtractor.Stream(url,mapOf("User-Agent" to USER_AGENT,"Referer" to "https://www.youtube.com/"),expires,isHls,true,if(isHls)"" else selected?.let {formatKey(it)}.orEmpty()),meta)
        }
        private fun formatKey(format: JSONObject): String {
            val tag=format.optString("itag");if(tag.isBlank())return ""
            val track=format.optJSONObject("audioTrack")?.optString("id").orEmpty()
            return tag+(if(track.isBlank())"" else ":$track")+(if(format.optBoolean("isDrc"))":drc" else "")
        }
        private fun isProgressiveAudio(format: JSONObject)=format.optString("mimeType").startsWith("audio/") &&
            format.optString("type")!="FORMAT_STREAM_TYPE_OTF" && format.optInt("targetDurationSec",0)==0 && !format.optString("url").contains("source/yt_otf")
        private fun extraAudio(id: String,root: JSONObject,quality: String,cpn: String): List<InnerTubeExtractor.Stream> {
            val data=root.optJSONObject("streamingData") ?: return emptyList()
            val formats=data.optJSONArray("adaptiveFormats") ?: return emptyList()
            return (0 until formats.length()).mapNotNull {i ->
                val f=formats.optJSONObject(i) ?: return@mapNotNull null
                if(!isProgressiveAudio(f) || f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault",true)==false || !usable(f.optString("url")))return@mapNotNull null
                val clone=JSONObject(root.toString());clone.getJSONObject("streamingData").put("adaptiveFormats",org.json.JSONArray().put(f)).remove("hlsManifestUrl")
                parse(id,clone,quality)?.stream?.let {it.copy(url=it.url.toHttpUrl().newBuilder().setQueryParameter("cpn",cpn).build().toString())}
            }.distinctBy {it.url}.sortedByDescending {stream -> Regex("[?&]bitrate=(\\d+)").find(stream.url)?.groupValues?.get(1)?.toLongOrNull() ?: 0 }
        }
        private fun usable(url: String)=url.startsWith("https://") && !Regex("[?&]n=").containsMatchIn(url)
    }
}
