package com.teejei.weddingapp.data

import android.content.Context
import android.net.Uri
import com.teejei.weddingapp.model.Guest
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free .xlsx writer (zip + XML), the counterpart of [ExcelReader].
 * Cells are written as inline strings / numbers, with a bold shaded header row and a frozen top row.
 */
object ExcelWriter {
    class Sheet(val name: String, val headers: List<String>, val rows: List<List<Any?>>, val widths: List<Int> = emptyList())

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun write(out: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "Nothing to export." }
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", HEAD +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", workbook(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", styles())
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun contentTypes(n: Int): String = buildString {
        append(HEAD)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..n) append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append(HEAD)
        append("<workbook xmlns=\"$NS\" xmlns:r=\"$REL_NS\"><sheets>")
        sheets.forEachIndexed { i, s -> append("<sheet name=\"${esc(safeName(s.name))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int): String = buildString {
        append(HEAD)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..n) append("<Relationship Id=\"rId$i\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        append("<Relationship Id=\"rId${n + 1}\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun styles(): String = HEAD +
        "<styleSheet xmlns=\"$NS\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDE8D6\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun sheetXml(s: Sheet): String = buildString {
        append(HEAD)
        append("<worksheet xmlns=\"$NS\">")
        append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        if (s.widths.isNotEmpty()) {
            append("<cols>")
            s.widths.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
            append("</cols>")
        }
        append("<sheetData>")
        append("<row r=\"1\">")
        s.headers.forEachIndexed { c, h -> append("<c r=\"${colName(c)}1\" s=\"1\" t=\"inlineStr\"><is><t>${esc(h)}</t></is></c>") }
        append("</row>")
        s.rows.forEachIndexed { r, row ->
            val rn = r + 2
            append("<row r=\"$rn\">")
            row.forEachIndexed { c, v ->
                val ref = "${colName(c)}$rn"
                when {
                    v == null -> {}
                    v is Number -> append("<c r=\"$ref\"><v>$v</v></c>")
                    else -> {
                        val t = v.toString()
                        if (t.isNotEmpty()) append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(t)}</t></is></c>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    private fun safeName(n: String) = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().ifEmpty { "Sheet" }.take(31)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
        }
        return sb.toString()
    }
}

/** Builds the guest-list workbook and writes it to a user-chosen location. */
object GuestExporter {
    private fun label(a: String) = when (a) { "attending" -> "Attending"; "not_attending" -> "Not attending"; else -> "Undecided" }

    /** Returns the number of guest groups exported. */
    fun export(ctx: Context, uri: Uri, guests: List<Guest>): Int {
        require(guests.isNotEmpty()) { "There are no guests to export yet." }
        val sorted = guests.sortedWith(compareBy({ it.groupName.isBlank() }, { it.groupName.lowercase() }))
        fun names(g: Guest, a: String) =
            g.seats.filter { it.attending == a }.joinToString(", ") { it.name.ifBlank { "Unnamed guest" } }

        // Headers match the importer's column names (token, group_name, max_pax, contact, url), so the file can be re-imported.
        val guestSheet = ExcelWriter.Sheet(
            name = "Guest Invi",
            headers = listOf("Token", "Group Name", "Max Pax", "Contact", "URL", "RSVP", "Invitation Sent", "Sent At", "Sent Notes",
                "First Opened", "Last Opened", "Visits", "Confirmed Seats", "Declined Seats", "Awaiting Seats",
                "Confirmed Names", "Declined Names"),
            rows = sorted.map { g ->
                listOf(g.token, g.groupName, g.maxPax, g.contact, g.url, g.rsvp.label, if (g.invitationSent) "Yes" else "No",
                    g.sentAt?.toString().orEmpty(), g.sentNotes, g.firstOpenedAt?.toString().orEmpty(),
                    g.lastOpenedAt?.toString().orEmpty(), g.visitCount, g.confirmedSeats, g.declinedSeats, g.awaitingSeats,
                    names(g, "attending"), names(g, "not_attending"))
            },
            widths = listOf(14, 28, 9, 16, 38, 12, 15, 22, 24, 22, 22, 8, 15, 15, 15, 40, 40)
        )
        val peopleSheet = ExcelWriter.Sheet(
            name = "People",
            headers = listOf("Group", "Seat No", "Name", "RSVP"),
            rows = sorted.flatMap { g ->
                g.seats.map { s -> listOf(g.groupName.ifBlank { "(unnamed group)" }, s.seatNo, s.name, label(s.attending)) }
            },
            widths = listOf(28, 9, 28, 16)
        )
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { ExcelWriter.write(it, listOf(guestSheet, peopleSheet)) }
            ?: error("Could not write to the selected location.")
        return sorted.size
    }
}
