package me.avinas.tempo.data.deezer

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerXlsxParserTest {

    @Test
    fun parsesOfficialListeningHistoryShape() {
        val file = createWorkbook(includeHistory = true)
        try {
            val result = DeezerXlsxParser.parse(file)

            assertEquals(1, result.entries.size)
            assertEquals(0, result.malformedRows)

            val entry = result.entries.single()
            assertEquals("Never Gonna Give You Up", entry.trackName)
            assertEquals("Rick Astley", entry.artistName)
            assertEquals("Whenever You Need Somebody", entry.albumName)
            assertEquals("GBAYE8800243", entry.isrc)
            assertEquals(213_000L, entry.msPlayed)
            assertEquals(
                Instant.parse("2024-10-24T23:00:00Z").toEpochMilli(),
                entry.listenedAtMillis,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun findsListeningHistoryBySheetNameNotPosition() {
        val file = createWorkbook(includeHistory = true, putHistorySecond = true)
        try {
            val result = DeezerXlsxParser.parse(file)
            assertEquals(1, result.entries.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsWorkbookWithoutDeezerListeningHistorySheet() {
        val file = createWorkbook(includeHistory = false)
        try {
            val error = runCatching { DeezerXlsxParser.parse(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("10_listeningHistory"))
        } finally {
            file.delete()
        }
    }

    private fun createWorkbook(
        includeHistory: Boolean,
        putHistorySecond: Boolean = false,
    ): File {
        val file = kotlin.io.path.createTempFile("deezer-test-", ".xlsx").toFile()

        val sheets =
            if (includeHistory && putHistorySecond) {
                listOf("00_userProfile" to "rId1", "10_listeningHistory" to "rId2")
            } else if (includeHistory) {
                listOf("10_listeningHistory" to "rId1")
            } else {
                listOf("00_userProfile" to "rId1")
            }

        val workbookSheets = sheets.mapIndexed { index, pair ->
            "<sheet name=\"" + pair.first + "\" sheetId=\"" + (index + 1) + "\" r:id=\"" + pair.second + "\"/>"
        }.joinToString("")

        val relationships = sheets.mapIndexed { index, pair ->
            "<Relationship Id=\"" + pair.second + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet" + (index + 1) + ".xml\"/>"
        }.joinToString("")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            writeEntry(
                zip,
                "xl/workbook.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets>""" + workbookSheets + """</sheets>
                </workbook>
                """.trimIndent(),
            )
            writeEntry(
                zip,
                "xl/_rels/workbook.xml.rels",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    """ + relationships + """
                </Relationships>
                """.trimIndent(),
            )

            val shared = listOf(
                "Song Title",
                "Artist",
                "ISRC",
                "Album Title",
                "IP Address",
                "Listening Time",
                "Platform Name",
                "Platform Model",
                "Date",
                "Never Gonna Give You Up",
                "Rick Astley",
                "GBAYE8800243",
                "Whenever You Need Somebody",
                "2024-10-24 23:00:00",
            )
            writeEntry(
                zip,
                "xl/sharedStrings.xml",
                buildString {
                    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                    shared.forEach { value ->
                        append("<si><t>")
                        append(value)
                        append("</t></si>")
                    }
                    append("</sst>")
                },
            )

            sheets.forEachIndexed { index, pair ->
                val xml =
                    if (pair.first == "10_listeningHistory") {
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" t="s"><v>0</v></c>
                              <c r="B1" t="s"><v>1</v></c>
                              <c r="C1" t="s"><v>2</v></c>
                              <c r="D1" t="s"><v>3</v></c>
                              <c r="E1" t="s"><v>4</v></c>
                              <c r="F1" t="s"><v>5</v></c>
                              <c r="G1" t="s"><v>6</v></c>
                              <c r="H1" t="s"><v>7</v></c>
                              <c r="I1" t="s"><v>8</v></c>
                            </row>
                            <row r="2">
                              <c r="A2" t="s"><v>9</v></c>
                              <c r="B2" t="s"><v>10</v></c>
                              <c r="C2" t="s"><v>11</v></c>
                              <c r="D2" t="s"><v>12</v></c>
                              <c r="F2"><v>213</v></c>
                              <c r="I2" t="s"><v>13</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                        """.trimIndent()
                    } else {
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData/>
                        </worksheet>
                        """.trimIndent()
                    }
                writeEntry(zip, "xl/worksheets/sheet" + (index + 1) + ".xml", xml)
            }
        }

        return file
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
