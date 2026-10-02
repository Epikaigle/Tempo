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
    fun `ignores a different exception type with the same message and frame`() {
        val error =
            RuntimeException("Broadcast already finished").apply {
                stackTrace =
                    arrayOf(
                        frame("android.content.BroadcastReceiver\$PendingResult", "sendFinished", "BroadcastReceiver.java", 283),
                    )
            }

        assertFalse(FrameworkRaceGuard.isDeferredBroadcastFinishRace(error))
    }

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
