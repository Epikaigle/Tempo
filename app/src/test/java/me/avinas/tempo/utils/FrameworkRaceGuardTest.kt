package me.avinas.tempo.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that [FrameworkRaceGuard.isDeferredBroadcastFinishRace] matches the specific
 * AOSP b/257513022 crash signature while rejecting unrelated exceptions.
 */
class FrameworkRaceGuardTest {
    @Test
    fun `matches the real deferred finish race`() {
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                        frame("android.content.BroadcastReceiver\$PendingResult\$1", "run", "BroadcastReceiver.java", 256),
                        frame("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
                        frame("android.app.QueuedWork\$QueuedWorkHandler", "handleMessage", "QueuedWork.java", 285),
                        frame("android.os.Handler", "dispatchMessage", "Handler.java", 106),
                        frame("android.os.HandlerThread", "run", "HandlerThread.java", 67),
                    ),
            )

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `matches when sendFinished is not the top frame`() {
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("com.example.Other", "helper", "Other.java", 1),
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 248),
                        frame("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
                    ),
            )

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `matches when only the PendingResult finisher runnable is present`() {
        // Some OEM builds inline QueuedWork.processPendingWork into the runnable's stack.
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                        frame("android.content.BroadcastReceiver\$PendingResult\$1", "run", "BroadcastReceiver.java", 256),
                    ),
            )

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `ignores a direct double finish from app code`() {
        // App-level double finish has no QueuedWork frames and should not be suppressed.
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                        frame("me.avinas.tempo.SomeReceiver", "onReceive", "SomeReceiver.kt", 10),
                    ),
            )

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `ignores the same message from a different class`() {
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames = listOf(frame("me.avinas.tempo.SomeReceiver", "onReceive", "SomeReceiver.kt", 10)),
            )

        assertFalse(
            "A same-message ISE thrown from app code is a real bug and must still crash",
            FrameworkRaceGuard.isDeferredBroadcastFinishRace(error),
        )
    }

    @Test
    fun `ignores other IllegalStateExceptions from PendingResult`() {
        val error =
            illegalState(
                message = "BroadcastReceiver trying to return result during a non-ordered broadcast",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                    ),
            )

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `ignores a same-shaped race from a different framework method`() {
        val error =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "finish", "BroadcastReceiver.java", 252),
                    ),
            )

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    @Test
    fun `matches a different exception type with the same message and frame`() {
        val error =
            RuntimeException("Broadcast already finished").apply {
                stackTrace =
                    arrayOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                    )
            }

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

    // --- CRASH B: the race as it actually reaches the uncaught handler -------------------------

    /**
     * The reported crash, verbatim.
     *
     * `ActivityThread.handleStopService()` calls `QueuedWork.waitToFinish()` inside its `try`, so the
     * `IllegalStateException` is caught and re-thrown as
     * `RuntimeException("Unable to stop service " + s + ": " + e.toString(), e)`. The uncaught
     * throwable is therefore the wrapping `RuntimeException` — which is what the old
     * `error !is IllegalStateException` check rejected.
     */
    @Test
    fun `matches the reported handleStopService wrapper whose cause is the race`() {
        val race = illegalState(message = "Broadcast already finished", frames = crashBCauseFrames())
        val uncaught =
            RuntimeException(
                "Unable to stop service " +
                    "me.avinas.tempo.service.MusicTrackingService@1234: " +
                    "java.lang.IllegalStateException: Broadcast already finished",
                race,
            ).apply { stackTrace = crashBWrapperFrames() }

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught))
    }

    @Test
    fun `matches the wrapper when only the reported frames are present`() {
        // Same crash, built the way a crash reporter hands it over: the wrapper's own frames are the
        // looper frames, the cause carries the QueuedWork deferral.
        val race =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 275),
                        frame("android.content.BroadcastReceiver\$PendingResult\$1", "run", "BroadcastReceiver.java", 248),
                        frame("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
                        frame("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
                        frame("android.app.ActivityThread", "handleStopService", "ActivityThread.java", 5009),
                    ),
            )
        val uncaught =
            RuntimeException("Unable to stop service ServiceRecord{abc}", race).apply {
                stackTrace =
                    arrayOf(
                        frame("android.app.ActivityThread", "handleStopService", "ActivityThread.java", 5021),
                        frame("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2327),
                    )
            }

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught))
    }

    @Test
    fun `still matches the bare race when the framework does not wrap it`() {
        // handleStopService is not the only caller of waitToFinish: a bare ISE can still surface.
        val race = illegalState(message = "Broadcast already finished", frames = crashBCauseFrames())

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(race))
    }

    @Test
    fun `ignores a wrapping RuntimeException whose cause is not this race`() {
        val cause =
            illegalState(
                message = "BroadcastReceiver trying to return result during a non-ordered broadcast",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 275),
                        frame("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
                    ),
            )
        val uncaught =
            RuntimeException("Unable to stop service ServiceRecord{abc}", cause).apply {
                stackTrace = crashBWrapperFrames()
            }

        assertFalse(
            "A wrapper around an unrelated IllegalStateException is a real bug and must still crash",
            FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught),
        )
    }

    @Test
    fun `ignores a wrapping RuntimeException around an app-code double finish`() {
        // Right message, right class, but no QueuedWork deferral: this is a direct double finish.
        val cause =
            illegalState(
                message = "Broadcast already finished",
                frames =
                    listOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 275),
                        frame("me.avinas.tempo.receiver.SomeReceiver", "finish", "SomeReceiver.kt", 42),
                    ),
            )
        val uncaught =
            RuntimeException("Unable to stop service ServiceRecord{abc}", cause).apply {
                stackTrace = crashBWrapperFrames()
            }

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught))
    }

    @Test
    fun `ignores an unrelated RuntimeException with no cause`() {
        val uncaught =
            RuntimeException("Unable to stop service ServiceRecord{abc}").apply {
                stackTrace = crashBWrapperFrames()
            }

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught))
    }

    @Test
    fun `matches the race nested more than one link deep`() {
        val race = illegalState(message = "Broadcast already finished", frames = crashBCauseFrames())
        val middle = IllegalStateException("service teardown failed").apply { initCause(race) }
        val uncaught = RuntimeException("Unable to stop service ServiceRecord{abc}", middle)

        assertTrue(FrameworkRaceGuard.isDeferredBroadcastFinishRace(uncaught))
    }

    /** Frames of the `IllegalStateException` from the reported CRASH B stack. */
    private fun crashBCauseFrames(): List<StackTraceElement> =
        listOf(
            frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 275),
            frame("android.content.BroadcastReceiver\$PendingResult\$1", "run", "BroadcastReceiver.java", 248),
            frame("android.app.QueuedWork", "processPendingWork", "QueuedWork.java", 265),
            frame("android.app.QueuedWork", "waitToFinish", "QueuedWork.java", 178),
            frame("android.app.ActivityThread", "handleStopService", "ActivityThread.java", 5009),
        )

    /** Frames of the top-level `RuntimeException` from the reported CRASH B stack. */
    private fun crashBWrapperFrames(): Array<StackTraceElement> =
        arrayOf(
            frame("android.app.ActivityThread", "handleStopService", "ActivityThread.java", 5021),
            frame("android.app.ActivityThread", "access\$2400", "ActivityThread.java", 284),
            frame("android.app.ActivityThread\$H", "handleMessage", "ActivityThread.java", 2327),
            frame("android.os.Handler", "dispatchMessage", "Handler.java", 106),
            frame("android.os.Looper", "loopOnce", "Looper.java", 233),
            frame("android.os.Looper", "loop", "Looper.java", 334),
            frame("android.app.ActivityThread", "main", "ActivityThread.java", 8399),
        )

    private fun illegalState(
        message: String,
        frames: List<StackTraceElement>,
    ): IllegalStateException = IllegalStateException(message).apply { stackTrace = frames.toTypedArray() }

    private fun frame(
        className: String,
        methodName: String,
        fileName: String,
        lineNumber: Int,
    ): StackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
}
