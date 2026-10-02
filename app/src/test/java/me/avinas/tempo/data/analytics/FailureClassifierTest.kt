package me.avinas.tempo.data.analytics

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import java.util.zip.ZipException
import javax.net.ssl.SSLException

class FailureClassifierTest {

    /**
     * Order-sensitive cases matter here: SocketTimeoutException, SSLException and ZipException
     * all extend IOException, and CancellationException extends IllegalStateException. If the
     * branches are ever reordered, the more specific classes must still win.
     */
    @Test
    fun `maps failures onto the closed taxonomy`() {
        assertEquals(FailureClass.OUT_OF_MEMORY, FailureClassifier.of(OutOfMemoryError()))
        assertEquals(FailureClass.CANCELLED, FailureClassifier.of(CancellationException()))
        assertEquals(FailureClass.PARSE, FailureClassifier.of(ZipException("bad zip")))
        assertEquals(FailureClass.NETWORK, FailureClassifier.of(SSLException("tls")))
        assertEquals(FailureClass.NETWORK, FailureClassifier.of(UnknownHostException("dns")))
        assertEquals(FailureClass.NETWORK, FailureClassifier.of(SocketTimeoutException("slow")))
        assertEquals(FailureClass.NETWORK, FailureClassifier.of(TimeoutException("slow")))
        assertEquals(FailureClass.IO, FailureClassifier.of(IOException("disk")))
        assertEquals(FailureClass.PERMISSION, FailureClassifier.of(SecurityException("denied")))
        assertEquals(FailureClass.PARSE, FailureClassifier.of(IllegalStateException("unexpected")))
        assertEquals(FailureClass.PARSE, FailureClassifier.of(NumberFormatException("abc")))
    }

    @Test
    fun `anything unrecognised falls back to UNKNOWN rather than leaking a message`() {
        assertEquals(FailureClass.UNKNOWN, FailureClassifier.of(RuntimeException("Some Song Title")))
    }
}
