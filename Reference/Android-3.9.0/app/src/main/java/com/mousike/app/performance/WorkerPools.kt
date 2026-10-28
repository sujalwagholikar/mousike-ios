package com.mousike.app.performance

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A full queue fails quickly. Never run network work on the caller/UI thread. */
object WorkerPools {
    fun bounded(name: String, threads: Int, queued: Int = 8): ThreadPoolExecutor =
        ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(queued),
            { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
}
