package android.os

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * android.os stubs. Handler/Looper are FUNCTIONAL: plugins post UI/async work
 * through them, so posts run on a shared single-thread "main" executor.
 */
class Looper private constructor() {
    val thread: Thread = Thread(MAIN_THREAD_NAME)

    fun quit() {}

    companion object {
        private const val MAIN_THREAD_NAME = "desktop-main"

        private val mainLooperInstance: Looper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooperInstance

        @JvmStatic
        fun myLooper(): Looper? = mainLooperInstance

        @JvmStatic
        fun prepare() {}

        @JvmStatic
        fun loop() {}
    }
}

class Handler {
    interface Callback {
        fun handleMessage(msg: Message?): Boolean
    }

    private val looper: Looper

    constructor() { looper = Looper.getMainLooper() }
    constructor(looper: Looper?) { this.looper = looper ?: Looper.getMainLooper() }
    constructor(callback: Callback?) { looper = Looper.getMainLooper() }
    constructor(looper: Looper?, callback: Callback?) { this.looper = looper ?: Looper.getMainLooper() }

    fun post(runnable: Runnable?): Boolean {
        runnable ?: return false
        MainExecutor.execute(runnable)
        return true
    }

    fun postDelayed(runnable: Runnable?, delayMillis: Long): Boolean {
        runnable ?: return false
        MainExecutor.schedule(runnable, delayMillis)
        return true
    }

    fun postAtFrontOfQueue(runnable: Runnable?): Boolean = post(runnable)

    fun removeCallbacks(runnable: Runnable?) { MainExecutor.remove(runnable) }
    fun removeCallbacksAndMessages(token: Any?) { MainExecutor.cancelAll() }

    fun sendEmptyMessage(what: Int): Boolean = true
    fun sendMessage(msg: Message?): Boolean = true
    fun handleMessage(msg: Message?) {}

    companion object {
        internal object MainExecutor {
            private val executor: ScheduledExecutorService =
                Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "desktop-main").apply { isDaemon = true }
                }
            private val tasks = java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<Runnable, Boolean>()
            )

            fun execute(r: Runnable) {
                tasks.add(r)
                executor.execute { tasks.remove(r); r.run() }
            }

            fun schedule(r: Runnable, delayMs: Long) {
                tasks.add(r)
                val wrapped = Runnable { tasks.remove(r); r.run() }
                executor.schedule(wrapped, delayMs, TimeUnit.MILLISECONDS)
            }

            fun remove(r: Runnable?) { /* Java executor can't cancel queued tasks cheaply; no-op */ }

            fun cancelAll() { /* no-op */ }
        }
    }
}

class Message {
    var what: Int = 0
    var arg1: Int = 0
    var arg2: Int = 0
    var obj: Any? = null

    companion object {
        @JvmStatic
        fun obtain(): Message = Message()
    }
}
