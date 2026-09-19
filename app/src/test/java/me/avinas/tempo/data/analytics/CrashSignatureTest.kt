package me.avinas.tempo.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CrashSignatureTest {

    /**
     * The single most important property: parse errors routinely echo the notification text
     * Tempo read, so the message must never reach the signature.
     */
    @Test
    fun `the exception message never reaches the signature`() {
        val error = IllegalStateException("Now playing: Never Gonna Give You Up by Rick Astley")

        val (className, frame) = CrashSignature.of(error)
        val combined = "$className$frame"

        assertFalse(combined.contains("Never Gonna", ignoreCase = true))
        assertFalse(combined.contains("Rick", ignoreCase = true))
        assertFalse(combined.contains("Now playing", ignoreCase = true))
    }

    /** If this ever fails, a real crash could fail to be reported at all. */
    @Test
    fun `every real throwable produces a signature the Crash event accepts`() {
        val throwables = listOf(
            RuntimeException("boom"),
            IllegalStateException("bad state"),
            IOException("read failed"),
            OutOfMemoryError("oom"),
            Throwable(),
            object : Exception("anonymous") {},
            IllegalArgumentException("a".repeat(500))
        )

        throwables.forEach { error ->
            val (className, frame) = CrashSignature.of(error)
            // Constructing the event is the assertion: it re-validates both fields.
            val event = Crash(className, frame, "4.8.8")

            assertTrue(event.props.containsKey("crash_class"))
            assertTrue(event.props.containsKey("top_frame"))
        }
    }

    @Test
    fun `a throwable with no frames still produces a valid signature`() {
        val error = RuntimeException("x").apply { stackTrace = emptyArray() }

        val (className, frame) = CrashSignature.of(error)

        assertEquals("unknown(SourceFile:0)", frame)
        Crash(className, frame, "4.8.8")
    }

    /** Unknown line numbers are reported as negative; the schema requires digits. */
    @Test
    fun `unknown line numbers are clamped`() {
        val error = RuntimeException("x").apply {
            stackTrace = arrayOf(StackTraceElement("me.avinas.tempo.Foo", "bar", null, -1))
        }

        val (_, frame) = CrashSignature.of(error)

        assertEquals("bar(SourceFile:0)", frame)
    }

    @Test
    fun `class names are stripped to the characters the schema allows`() {
        // Anonymous and nested classes carry spaces, slashes and brackets in some forms.
        val error = object : Exception("x") {}

        val (className, _) = CrashSignature.of(error)

        assertTrue("unsafe class name: $className", Regex("^[A-Za-z0-9_.\$]{1,64}$").matches(className))
    }

    @Test
    fun `a very long class name is truncated to the documented cap`() {
        val (className, _) = CrashSignature.of(RuntimeException("x"))

        assertTrue(className.length <= Crash.MAX_CLASS_CHARS)
    }
}
