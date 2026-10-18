package me.avinas.tempo.utils

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the crash class behind `Canvas: trying to draw too large(Nbytes) bitmap.`
 *
 * A raster in `res/` is decoded by `BitmapFactory` at its own size scaled by the density bucket
 * ratio, and `painterResource` hands that bitmap straight to a `BitmapPainter`. A 4096x4096 PNG
 * in the unqualified (mdpi) `drawable/` bucket therefore decodes to 8192x8192 on a 320 dpi device
 * — 268,435,456 bytes of ARGB_8888, which a hardware Canvas rejects outright. That is exactly the
 * reported crash, so the invariant is checked here rather than left to reviewer memory: every
 * shipped raster, after the largest density scale Android can apply to its bucket, must stay
 * inside [MAX_SAFE_BITMAP_EDGE_PX].
 */
class BitmapResourceCeilingTest {

    /**
     * Ratio between the highest density Android defines (640 dpi / xxxhdpi) and mdpi (160 dpi).
     * An unqualified `drawable/` is an mdpi bucket, so this is the worst-case upscale it can get.
     */
    private val maxDensityScale = 4

    /** Density bucket scale factors, keyed by the directory qualifier. */
    private val bucketScales =
        mapOf(
            "ldpi" to 0.75,
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0,
        )

    @Test
    fun `every shipped raster stays inside the painter ceiling once density-scaled`() {
        val resDir = resDirectory()
        val rasters =
            resDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }
                .toList()

        assertTrue("expected to find raster resources under $resDir", rasters.isNotEmpty())

        val violations =
            rasters.mapNotNull { file ->
                val size = readPixelSize(file) ?: return@mapNotNull null
                val scale = worstCaseDensityScale(file.parentFile?.name ?: "")
                val decodedLongestEdge = maxOf(size.first, size.second) * scale
                if (decodedLongestEdge > MAX_SAFE_BITMAP_EDGE_PX) {
                    "${file.relativeTo(resDir)} is ${size.first}x${size.second}, " +
                        "which decodes to ${decodedLongestEdge}px on a high-density device " +
                        "(ceiling $MAX_SAFE_BITMAP_EDGE_PX)"
                } else {
                    null
                }
            }

        assertTrue(
            "raster resources can decode past the safe bitmap ceiling:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * The density scale that [bucketDir] can be rendered at: a density-qualified bucket is only
     * ever scaled up to the highest density Android defines, while an unqualified directory is an
     * mdpi bucket and can be scaled by the full 4x. `nodpi` is never rescaled.
     */
    private fun worstCaseDensityScale(bucketDir: String): Double {
        if (bucketDir.contains("nodpi") || bucketDir.contains("anydpi")) return 1.0
        val bucket =
            bucketScales.entries.firstOrNull { (name, _) ->
                bucketDir.substringAfter('-', "").split('-').any { it == name }
            }
        if (bucket == null) return maxDensityScale.toDouble()
        val scale = maxDensityScale / bucket.value
        return if (scale < 1.0) 1.0 else scale
    }

    @Test
    fun `res directory is discoverable from the test working directory`() {
        assertTrue(resDirectory().isDirectory)
    }

    private fun resDirectory(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate res/ from ${File("").absolutePath}")
    }

    /** Reads the pixel dimensions from a raster header; null when the format is not parsed. */
    private fun readPixelSize(file: File): Pair<Int, Int>? {
        val header = ByteArray(32)
        val read = file.inputStream().use { it.read(header) }
        if (read < 16) return null
        return when {
            header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() -> pngSize(file)
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> jpegSize(file)
            header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() -> gifSize(header)
            header[0] == 'R'.code.toByte() && header[8] == 'W'.code.toByte() -> webpSize(file)
            else -> null
        }
    }

    private fun pngSize(file: File): Pair<Int, Int>? {
        val header = ByteArray(26)
        file.inputStream().use { it.read(header) }
        return Pair(beInt(header, 16), beInt(header, 20))
    }

    private fun gifSize(header: ByteArray): Pair<Int, Int> =
        Pair(leShort(header, 6), leShort(header, 8))

    /** Walks the JPEG segment chain to the first SOFn frame header. */
    private fun jpegSize(file: File): Pair<Int, Int>? {
        val bytes = file.readBytes()
        var index = 2
        while (index + 9 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return Pair(beShort(bytes, index + 7), beShort(bytes, index + 5))
            }
            val length = beShort(bytes, index + 2)
            if (length < 2) return null
            index += 2 + length
        }
        return null
    }

    /** Parses the VP8X / VP8 / VP8L chunk headers of a RIFF/WebP container. */
    private fun webpSize(file: File): Pair<Int, Int>? {
        val bytes = file.readBytes()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourCc = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            val body = offset + 8
            when (fourCc) {
                "VP8X" ->
                    return Pair(
                        1 + (bytes[body + 4].toInt() and 0xFF) +
                            ((bytes[body + 5].toInt() and 0xFF) shl 8) +
                            ((bytes[body + 6].toInt() and 0xFF) shl 16),
                        1 + (bytes[body + 7].toInt() and 0xFF) +
                            ((bytes[body + 8].toInt() and 0xFF) shl 8) +
                            ((bytes[body + 9].toInt() and 0xFF) shl 16),
                    )
                "VP8 " ->
                    return Pair(
                        leShort(bytes, body + 6) and 0x3FFF,
                        leShort(bytes, body + 8) and 0x3FFF,
                    )
                "VP8L" -> {
                    val bits = leInt(bytes, body + 1)
                    return Pair((bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1)
                }
            }
            if (size <= 0) return null
            offset = body + size + (size % 2)
        }
        return null
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun beShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
