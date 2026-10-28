package com.mousike.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mousike.app.R
import com.mousike.app.data.model.TrackCodec
import com.mousike.app.playback.InnerTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MusicDownloadWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    private fun foreground(title: String,percent: Int): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads","Music downloads",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(applicationContext,"downloads").setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title).setContentText("Downloading for offline listening").setProgress(100,percent,percent==0).setOngoing(true).setSilent(true).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode().and(Int.MAX_VALUE),n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode().and(Int.MAX_VALUE),n)
    }
    override suspend fun doWork(): Result {
        val raw=inputData.getString("track") ?: return Result.failure()
        val track=runCatching { TrackCodec.decode(JSONObject(raw)) }.getOrNull() ?: return Result.failure()
        val store=DownloadStore.get(applicationContext)
        return try {
            setForeground(foreground(track.title,0)); store.update(DownloadEntry(track,"Downloading"))
            val base=DownloadStore.workName(track.id)
            val output=withContext(Dispatchers.IO) {
                if(track.streamUrl.startsWith("asset:///")) {
                    File(store.outputDirectory(),"$base.wav").also { target -> applicationContext.assets.open(track.streamUrl.removePrefix("asset:///" )).use { src -> target.outputStream().use { src.copyTo(it) } } }
                } else {
                    val extractor=InnerTubeExtractor.get(applicationContext)
                    val stream=runInterruptible {extractor.resolve(track.videoId,quality=com.mousike.app.settings.AppSettings.get(applicationContext).state.value.audioQuality)}
                    var lastProgress=-1
                    fun progress(value: Int) {
                        val percent=value.coerceIn(0,99)
                        if(isStopped || Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Download cancelled")
                        if(percent!=lastProgress){lastProgress=percent;store.update(DownloadEntry(track,"Downloading",percent));setProgressAsync(workDataOf("progress" to percent))}
                    }
                    if(stream.isHls) {
                        val selectedKeys=mutableListOf<androidx.media3.common.StreamKey>()
                        val downloader=runInterruptible {OfflineAudio.downloader(applicationContext,stream,com.mousike.app.settings.AppSettings.get(applicationContext).state.value.audioQuality,selectedKeys)}
                        try {runInterruptible {downloader.download {_,_,percent ->progress(if(percent<0)0 else percent.toInt())}}}
                        finally {downloader.cancel()}
                        File(store.outputDirectory(),"$base.mousike-hls.json").also {it.writeText(JSONObject().put("url",stream.url).put("keys",org.json.JSONArray(selectedKeys.map {org.json.JSONArray(listOf(it.groupIndex,it.streamIndex))})).toString())}
                    } else {
                        val sourceKey=DownloadStore.workName("source:${track.videoId}:${com.mousike.app.settings.AppSettings.get(applicationContext).state.value.audioQuality}")
                        val target=File(store.outputDirectory(),"$sourceKey.audio")
                        val temporary=File(store.outputDirectory(),"$sourceKey.audio.part")
                        runInterruptible { OfflineAudio.withSourceLock(sourceKey) {
                          if(!target.isFile || target.length()==0L) {
                            val source=com.mousike.app.playback.ResumingAudioDataSource.Factory(com.mousike.app.playback.AudioNetwork.http,stream.headers).createDataSource()
                            try {
                                val length=source.open(androidx.media3.datasource.DataSpec.Builder().setUri(stream.url).build())
                                temporary.outputStream().use {out ->val buffer=ByteArray(128*1024);var written=0L
                                    while(true){progress(if(length>0)(written*100/length).toInt() else 0);val count=source.read(buffer,0,buffer.size);if(count==androidx.media3.common.C.RESULT_END_OF_INPUT)break;if(count>0){out.write(buffer,0,count);written+=count}}
                                }
                                check(temporary.length()>0 && (length<0 || temporary.length()==length)){"Incomplete audio download"}
                                check(temporary.renameTo(target)){"Could not finish audio download"}
                            } finally {source.close();temporary.delete()}
                          }
                        } }
                        // Whole-source audio preserves reliable offsets without a transcoder/yt-dlp process.
                        File(target.absolutePath+".source.json").writeText(JSONObject().put("videoId",track.videoId).toString())
                        target
                    }
                }
            }
            check(output.length()>0) { "The audio file is empty" }
            store.update(DownloadEntry(track,"Complete",100,output.absolutePath)); Result.success()
        } catch(e: kotlinx.coroutines.CancellationException) {
            store.update(DownloadEntry(track,"Cancelled")); throw e
        } catch(e: Exception) {
            store.update(DownloadEntry(track,"Failed",error="Download failed. Check connection or try a source with compatible offline audio."))
            Result.failure()
        }
    }
}
