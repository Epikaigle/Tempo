package me.avinas.tempo.data.analytics

import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.zip.ZipException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/**
 * Maps a throwable onto the closed [FailureClass] taxonomy.
 *
 * Exists so failure events carry a stable category instead of a message. Mapping happens here,
 * in one tested place, rather than each call site inventing its own classification — which
 * would quietly reintroduce free text into the schema.
 */
internal object FailureClassifier {

    fun of(error: Throwable): FailureClass = when (error) {
        is OutOfMemoryError -> FailureClass.OUT_OF_MEMORY
        is CancellationException -> FailureClass.CANCELLED
        is ZipException -> FailureClass.PARSE
        is org.json.JSONException -> FailureClass.PARSE
        is SSLException -> FailureClass.NETWORK
        is TimeoutException -> FailureClass.NETWORK
        is java.net.SocketTimeoutException -> FailureClass.NETWORK
        is java.net.UnknownHostException -> FailureClass.NETWORK
        is IOException -> FailureClass.IO
        is SecurityException -> FailureClass.PERMISSION
        is IllegalStateException -> FailureClass.PARSE
        is NumberFormatException -> FailureClass.PARSE
        else -> FailureClass.UNKNOWN
    }
}
