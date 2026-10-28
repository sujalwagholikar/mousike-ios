package com.mousike.app.performance

import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation reaches in-flight HTTP calls and queued Python work. */
class ResolutionSignal {
    private val stopped=AtomicBoolean(false)
    private val hooks=CopyOnWriteArrayList<()->Unit>()
    val isCancelled: Boolean get()=stopped.get()
    fun check() {if(isCancelled || Thread.currentThread().isInterrupted)throw InterruptedIOException("Playback request cancelled")}
    fun onCancel(hook: ()->Unit) {hooks.add(hook);if(isCancelled)runCatching(hook)}
    fun cancel() {if(stopped.compareAndSet(false,true)){hooks.forEach {runCatching(it)};hooks.clear()}}
}

object BoundedResolution {
    private val workers=WorkerPools.bounded("python-playback",3)
    private val catalogWorkers=WorkerPools.bounded("python-catalog",2,4)
    fun <T> run(timeoutMs: Long,signal: ResolutionSignal,background: Boolean=false,operation: ()->T): T {
        signal.check()
        val executor=if(background)catalogWorkers else workers
        executor.purge()
        val task=try {executor.submit(Callable {signal.check();operation()})}
        catch(_: RejectedExecutionException){throw InterruptedIOException("Too many requests. Please retry.")}
        signal.onCancel {task.cancel(true)}
        try {return task.get(timeoutMs,TimeUnit.MILLISECONDS)}
        catch(e: TimeoutException) {signal.cancel();throw InterruptedIOException("The audio connection took too long.")}
        catch(e: InterruptedException) {signal.cancel();Thread.currentThread().interrupt();throw InterruptedIOException("Playback request cancelled")}
        catch(e: CancellationException) {throw InterruptedIOException("Playback request cancelled")}
        catch(e: ExecutionException) {throw (e.cause ?: e)}
        finally {if(!task.isDone)task.cancel(true);executor.purge()}
    }
}
