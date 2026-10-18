package me.avinas.tempo.utils

import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Uncaught-exception filter for a known platform race condition (AOSP b/257513022).
 *
 * When manifest broadcast receivers finish while SharedPreferences writes are pending,
 * `PendingResult.finish()` defers `sendFinished()` to the `QueuedWork` handler thread.
 * If the broadcast result was already reported, this throws an uncatchable
 * `IllegalStateException("Broadcast already finished")` that terminates the process.
 *
 * The throwable that reaches the uncaught handler is usually NOT that
 * `IllegalStateException` itself: `ActivityThread.handleStopService()` calls
 * `QueuedWork.waitToFinish()` inside its own `try`, so the sync throw is caught and re-thrown as
 *
 * ```java
 * throw new RuntimeException("Unable to stop service " + s + ": " + e.toString(), e);
 * ```
 *
 * (`ActivityThread.java`, `handleStopService`). The uncaught throwable is therefore a
 * `java.lang.RuntimeException` whose *cause* is the race, which is why the race signature has to be
 * looked for along the cause chain rather than on the top-level throwable alone.
 *
 * Matches only when some throwable in the chain meets all of:
 * 1. It is an `IllegalStateException`
 * 2. Its message is exactly "Broadcast already finished"
 * 3. Its stack trace includes `PendingResult.sendFinished` originating from `QueuedWork`
 */
object FrameworkRaceGuard {
    private const val TAG = "FrameworkRaceGuard"

    /** Process exit code used when the main looper is lost to the race. */
    private const val EXIT_MAIN_LOOPER_LOST = 11

    private const val BROADCAST_ALREADY_FINISHED = "Broadcast already finished"
    private const val PENDING_RESULT_CLASS = "android.content.BroadcastReceiver\$PendingResult"
    private const val SEND_FINISHED = "sendFinished"

    private const val PENDING_RESULT_FINISHER = "android.content.BroadcastReceiver\$PendingResult\$1"
    private const val QUEUED_WORK_CLASS = "android.app.QueuedWork"

    /**
     * Bound on the cause-chain walk. Real chains are one or two links deep; the cap only exists so a
     * malformed cycle cannot spin the uncaught-exception handler.
     */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * Installs the guard as the process-wide uncaught-exception handler, delegating everything it
     * does not recognise to the handler that was previously installed.
     *
     * Call this after [me.avinas.tempo.data.analytics.CrashSignatureRecorder.install] so that
     * genuine crashes are still recorded by the recorder, while a suppressed race never reaches
     * it (and is therefore never reported as a crash).
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (isDeferredBroadcastFinishRace(error)) {
                // Suppress the duplicate finish on the QueuedWork thread to prevent process death.
                Log.e(TAG, "Suppressed deferred broadcast-finish race on thread '${thread.name}'", error)
                if (isMainThread(thread)) {
                    // This variant escapes `Looper.loop()`, so the main thread is already unwinding
                    // and the process can never dispatch another message — it would linger as an
                    // unresponsive process instead of a crash. Exit cleanly so the system can
                    // restart the app, and so no dead-looper process is left holding a foreground
                    // service. Reached on the service-teardown path, where `handleStopService`
                    // calls `QueuedWork.waitToFinish()`.
                    Log.e(TAG, "Race escaped the main looper; exiting so the app can be restarted")
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(EXIT_MAIN_LOOPER_LOST)
                }
            } else if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Log.e(TAG, "Unhandled exception on thread '${thread.name}' with no delegate handler", error)
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /**
     * Whether [thread] is the thread that runs the main looper and therefore cannot be replaced.
     *
     * Falls back to the thread name because this runs inside an uncaught-exception handler, where
     * throwing would lose the original crash.
     */
    private fun isMainThread(thread: Thread): Boolean =
        runCatching { Looper.getMainLooper()?.thread === thread }.getOrDefault(thread.name == "main")

    /**
     * True only for the deferred `PendingResult.sendFinished` double-finish race.
     *
     * Walks the cause chain because the framework wraps the race in an
     * `Unable to stop service ...` [RuntimeException] before it leaves the looper (see the class
     * KDoc). The match itself is unchanged and stays strict: one link of the chain must be an
     * `IllegalStateException` carrying the exact race message and the `QueuedWork` deferral frames,
     * so an unrelated `RuntimeException` — with or without a cause — still crashes the app.
     *
     * Kept `internal` so it can be unit-tested against real stack traces without installing a
     * process-wide handler.
     */
    internal fun isDeferredBroadcastFinishRace(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (isDeferredFinish(current)) return true
            val next = current.cause
            current = if (next === current) null else next
            depth++
        }
        return false
    }

    /** The un-wrapped signature: one throwable that is itself the deferred double-finish. */
    private fun isDeferredFinish(error: Throwable): Boolean {
        if (error !is IllegalStateException) return false
        if (error.message != BROADCAST_ALREADY_FINISHED) return false

        val frames = error.stackTrace
        val threwFromSendFinished =
            frames.any { frame ->
                frame.className == PENDING_RESULT_CLASS && frame.methodName == SEND_FINISHED
            }
        if (!threwFromSendFinished) return false

        // Must originate from QueuedWork deferral. Direct finish() calls from app code are not filtered.
        return frames.any { frame ->
            frame.className.startsWith(QUEUED_WORK_CLASS) || frame.className == PENDING_RESULT_FINISHER
        }
    }
}
