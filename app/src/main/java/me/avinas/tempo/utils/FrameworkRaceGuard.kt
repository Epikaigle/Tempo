package me.avinas.tempo.utils

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
 * Matches only when all conditions are met:
 * 1. Throwable is `IllegalStateException`
 * 2. Message is exactly "Broadcast already finished"
 * 3. Stack trace includes `PendingResult.sendFinished` originating from `QueuedWork`
 */
object FrameworkRaceGuard {
    private const val TAG = "FrameworkRaceGuard"

    private const val BROADCAST_ALREADY_FINISHED = "Broadcast already finished"
    private const val PENDING_RESULT_CLASS = "android.content.BroadcastReceiver\$PendingResult"
    private const val SEND_FINISHED = "sendFinished"

    private const val PENDING_RESULT_FINISHER = "android.content.BroadcastReceiver\$PendingResult\$1"
    private const val QUEUED_WORK_CLASS = "android.app.QueuedWork"

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
     * True only for the deferred `PendingResult.sendFinished` double-finish race.
     *
     * Kept `internal` so it can be unit-tested against real stack traces without installing a
     * process-wide handler.
     */
    internal fun isDeferredBroadcastFinishRace(error: Throwable): Boolean {
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
