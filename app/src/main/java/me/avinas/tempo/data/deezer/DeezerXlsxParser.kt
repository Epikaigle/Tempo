package me.avinas.tempo.data.deezer

import java.io.FilterInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * Streaming-friendly reader for the official Deezer personal-data XLSX export.
 *
 * Deezer stores listening history in the worksheet named "10_listeningHistory".
 * Only the fields required by Tempo are read; IP address and device/platform
 * information are deliberately ignored.
 */
object DeezerXlsxParser {
    private const val HISTORY_SHEET = "10_listeningHistory"
    private const val MAX_XML_ENTRY_BYTES = 256L * 1024 * 1024
    private const val MAX_SHARED_STRINGS = 2_000_000
    private const val MAX_HISTORY_ROWS = 2_000_000
    private const val MAX_STRING_LENGTH = 500

    private val deezerDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class Entry(
        val trackName: String,
        val artistName: String,
        val albumName: String?,
        val isrc: String?,
        val listenedAtMillis: Long,
        val msPlayed: Long,
    )

    data class ParseResult(
        val entries: List<Entry>,
        val malformedRows: Int,
    )

    fun parse(file: File): ParseResult {
        ZipFile(file).use { zip ->
            val sheetPath = findHistorySheetPath(zip)
            val sharedStrings = readSharedStrings(zip)
            val sheetEntry = zip.getEntry(sheetPath)
                ?: throw IllegalArgumentException("Deezer listening-history worksheet is missing")
            validateEntrySize(sheetEntry.size, sheetPath)

            zip.getInputStream(sheetEntry).use { raw ->
                return parseHistorySheet(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), sharedStrings)
            }
        }
    }

    private fun findHistorySheetPath(zip: ZipFile): String {
        val workbook = zip.getEntry("xl/workbook.xml")
            ?: throw IllegalArgumentException("Invalid XLSX: workbook.xml is missing")
        validateEntrySize(workbook.size, workbook.name)

        var relationshipId: String? = null
        zip.getInputStream(workbook).use { raw ->
            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if ((localName == "sheet" || qName == "sheet") &&
                        attributes.getValue("name") == HISTORY_SHEET
                    ) {
                        relationshipId =
                            attributes.getValue(
                                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                                "id",
                            ) ?: attributes.getValue("r:id")
                    }
                }
            })
        }

        val targetId = relationshipId
            ?: throw IllegalArgumentException(
                "This XLSX does not contain Deezer's $HISTORY_SHEET listening-history sheet",
            )

        val rels = zip.getEntry("xl/_rels/workbook.xml.rels")
            ?: throw IllegalArgumentException("Invalid XLSX: workbook relationships are missing")
        validateEntrySize(rels.size, rels.name)

        var target: String? = null
        zip.getInputStream(rels).use { raw ->
            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if ((localName == "Relationship" || qName == "Relationship") &&
                        attributes.getValue("Id") == targetId
                    ) {
                        target = attributes.getValue("Target")
                    }
                }
            })
        }

        val rawTarget = target
            ?: throw IllegalArgumentException("Invalid XLSX: listening-history relationship is missing")
        val normalized = rawTarget.replace('\\', '/').removePrefix("/")
        if (normalized.split('/').any { it == ".." }) {
            throw IllegalArgumentException("Invalid XLSX worksheet path")
        }
        return if (normalized.startsWith("xl/")) normalized else "xl/$normalized"
    }

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        validateEntrySize(entry.size, entry.name)

        val strings = ArrayList<String>()
        zip.getInputStream(entry).use { raw ->
            var insideItem = false
            var insideText = false
            var builder: StringBuilder? = null

            parseXml(LimitedInputStream(raw, MAX_XML_ENTRY_BYTES), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    when (localName ?: qName) {
                        "si" -> {
                            if (strings.size >= MAX_SHARED_STRINGS) {
                                throw IllegalArgumentException("Deezer XLSX contains too many shared strings")
                            }
                            insideItem = true
                            builder = StringBuilder()
                        }
                        "t" -> if (insideItem) insideText = true
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (insideItem && insideText) {
                        builder?.append(ch, start, length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    when (localName ?: qName) {
                        "t" -> insideText = false
                        "si" -> {
                            strings.add(builder?.toString().orEmpty())
                            builder = null
                            insideItem = false
                        }
                    }
                }
            })
        }
        return strings
    }

    private fun parseHistorySheet(
        input: InputStream,
        sharedStrings: List<String>,
    ): ParseResult {
        val entries = ArrayList<Entry>()
        var malformedRows = 0
        var headers: Map<String, Int>? = null
        var rowCount = 0

        var currentRow = LinkedHashMap<Int, String>()
        var currentCellColumn = -1
        var currentCellType: String? = null
        var collectingValue = false
        var cellValue = StringBuilder()

        parseXml(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (localName ?: qName) {
                    "row" -> {
                        rowCount++
                        if (rowCount > MAX_HISTORY_ROWS) {
                            throw IllegalArgumentException("Deezer listening history is too large")
                        }
                        currentRow = LinkedHashMap()
                    }
                    "c" -> {
                        currentCellColumn = columnIndex(attributes.getValue("r").orEmpty())
                        currentCellType = attributes.getValue("t")
                        cellValue = StringBuilder()
                    }
                    "v", "t" -> collectingValue = currentCellColumn >= 0
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collectingValue) cellValue.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName ?: qName) {
                    "v", "t" -> collectingValue = false
                    "c" -> {
                        if (currentCellColumn >= 0) {
                            currentRow[currentCellColumn] = decodeCell(
                                cellValue.toString(),
                                currentCellType,
                                sharedStrings,
                            )
                        }
                        currentCellColumn = -1
                        currentCellType = null
                    }
                    "row" -> {
                        val activeHeaders = headers
                        if (activeHeaders == null) {
                            val candidate = currentRow.entries
                                .associate { normalizeHeader(it.value) to it.key }
                            if (REQUIRED_HEADERS.all { it in candidate }) {
                                headers = candidate
                            }
                        } else if (currentRow.isNotEmpty()) {
                            val parsed = parseDataRow(currentRow, activeHeaders)
                            if (parsed != null) entries.add(parsed) else malformedRows++
                        }
                    }
                }
            }
        })

        if (headers == null) {
            throw IllegalArgumentException(
                "Deezer listening-history columns were not found in $HISTORY_SHEET",
            )
        }
        return ParseResult(entries, malformedRows)
    }

    private fun parseDataRow(
        row: Map<Int, String>,
        headers: Map<String, Int>,
    ): Entry? {
        fun value(header: String): String =
            headers[normalizeHeader(header)]?.let { row[it] }.orEmpty().trim()

        val title = sanitize(value("Song Title"))
        val artist = sanitize(value("Artist"))
        if (title.isBlank() || artist.isBlank()) return null

        val dateValue = value("Date")
        val timestamp = parseDate(dateValue)
        if (timestamp <= 0L) return null

        val listeningSeconds = value("Listening Time").replace(',', '.').toDoubleOrNull()
            ?: return null
        if (!listeningSeconds.isFinite() || listeningSeconds < 0.0) return null
        val msPlayed = (listeningSeconds * 1000.0)
            .coerceAtMost(24.0 * 60.0 * 60.0 * 1000.0)
            .toLong()

        return Entry(
            trackName = title,
            artistName = artist,
            albumName = sanitize(value("Album Title")).takeIf { it.isNotBlank() },
            isrc = sanitize(value("ISRC")).uppercase().takeIf { it.isNotBlank() },
            listenedAtMillis = timestamp,
            msPlayed = msPlayed,
        )
    }

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L

        runCatching {
            return LocalDateTime.parse(value.trim(), deezerDateFormatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }
        runCatching { return Instant.parse(value.trim()).toEpochMilli() }
        runCatching {
            return java.time.OffsetDateTime.parse(value.trim())
                .toInstant()
                .toEpochMilli()
        }

        // Some spreadsheet writers store dates as Excel serial numbers.
        val serial = value.trim().toDoubleOrNull()
        if (serial != null && serial > 0.0) {
            val millisPerDay = 86_400_000.0
            val excelEpoch = LocalDateTime.of(1899, 12, 30, 0, 0)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
            return (excelEpoch + serial * millisPerDay).toLong()
        }
        return 0L
    }

    private fun decodeCell(
        rawValue: String,
        cellType: String?,
        sharedStrings: List<String>,
    ): String = when (cellType) {
        "s" -> rawValue.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
        else -> rawValue
    }

    private fun columnIndex(reference: String): Int {
        if (reference.isBlank()) return -1
        var result = 0
        var letters = 0
        for (char in reference) {
            if (!char.isLetter()) break
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
            letters++
        }
        return if (letters == 0) -1 else result - 1
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace("\u00a0", " ").replace(Regex("\\s+"), " ")

    private fun sanitize(value: String): String = value.trim().take(MAX_STRING_LENGTH)

    private fun validateEntrySize(size: Long, name: String) {
        if (size > MAX_XML_ENTRY_BYTES) {
            throw IllegalArgumentException("XLSX entry is too large: $name")
        }
    }

    private fun parseXml(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newSAXParser().parse(input, handler)
    }

    private class LimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var readBytes = 0L

        private fun account(count: Int) {
            if (count <= 0) return
            readBytes += count
            if (readBytes > maxBytes) {
                throw IllegalArgumentException("XLSX XML entry exceeds safe size limit")
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) account(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also(::account)
    }

    private val REQUIRED_HEADERS = setOf(
        normalizeHeader("Song Title"),
        normalizeHeader("Artist"),
        normalizeHeader("Listening Time"),
        normalizeHeader("Date"),
    )
}
