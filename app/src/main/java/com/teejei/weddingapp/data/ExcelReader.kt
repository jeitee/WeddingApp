package com.teejei.weddingapp.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.TreeMap
import java.util.zip.ZipInputStream

/**
 * Minimal, dependency-free .xlsx reader (zip + XmlPullParser). Avoids Apache POI's size and
 * Android-compatibility problems. Reads one worksheet into header-keyed rows.
 */
object ExcelReader {
    data class SheetData(val sheetName: String, val headers: List<String>, val rows: List<Map<String, String>>)

    fun read(input: InputStream, preferredSheet: String = "Guest Invi"): SheetData {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.startsWith("xl/") && (e.name.endsWith(".xml") || e.name.endsWith(".rels"))) {
                    parts[e.name] = zip.readBytes()
                }
                zip.closeEntry(); e = zip.nextEntry
            }
        }
        val workbook = parts["xl/workbook.xml"] ?: error("Not a valid .xlsx file (workbook.xml missing).")
        val sheets = parseSheets(workbook)
        require(sheets.isNotEmpty()) { "The workbook has no sheets." }
        val rels = parseRels(parts["xl/_rels/workbook.xml.rels"])
        val chosen = sheets.firstOrNull { it.first.equals(preferredSheet, true) } ?: sheets.first()
        val target = rels[chosen.second] ?: error("Sheet relationship not found.")
        val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
        val sheetXml = parts[path] ?: error("Worksheet data not found ($path).")
        val shared = parseShared(parts["xl/sharedStrings.xml"])
        val grid = parseGrid(sheetXml, shared)

        val headerRowIdx = grid.keys.firstOrNull() ?: return SheetData(chosen.first, emptyList(), emptyList())
        val headerCells = grid[headerRowIdx]!!
        val headers = HashMap<Int, String>()
        headerCells.forEach { (c, v) -> headers[c] = v.trim().lowercase().replace(Regex("\\s+"), "_") }
        val rows = grid.tailMap(headerRowIdx, false).values.map { cells ->
            val m = LinkedHashMap<String, String>()
            headers.forEach { (c, h) -> m[h] = cells[c].orEmpty() }
            m as Map<String, String>
        }
        return SheetData(chosen.first, headers.toSortedMap().values.toList(), rows)
    }

    private fun parser(b: ByteArray) = Xml.newPullParser().apply { setInput(ByteArrayInputStream(b), null) }

    private fun parseSheets(b: ByteArray): List<Pair<String, String>> {
        val p = parser(b); val out = ArrayList<Pair<String, String>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = p.getAttributeValue(null, "name") ?: ""
                var rid = ""
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == "id") rid = p.getAttributeValue(i)
                out.add(name to rid)
            }
            ev = p.next()
        }
        return out
    }

    private fun parseRels(b: ByteArray?): Map<String, String> {
        if (b == null) return emptyMap()
        val p = parser(b); val out = HashMap<String, String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                out[p.getAttributeValue(null, "Id") ?: ""] = p.getAttributeValue(null, "Target") ?: ""
            }
            ev = p.next()
        }
        return out
    }

    private fun parseShared(b: ByteArray?): List<String> {
        if (b == null) return emptyList()
        val p = parser(b); val out = ArrayList<String>()
        var sb: StringBuilder? = null; var inT = false; var phonetic = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT && phonetic == 0) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "si" -> { out.add(sb?.toString() ?: ""); sb = null }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int =
        ref.takeWhile { it.isLetter() }.fold(0) { a, c -> a * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

    private fun parseGrid(b: ByteArray, shared: List<String>): TreeMap<Int, TreeMap<Int, String>> {
        val grid = TreeMap<Int, TreeMap<Int, String>>()
        val p = parser(b)
        var row = 0; var col = 0; var type = ""; var inCell = false; var inV = false; var inT = false
        val cur = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (row + 1)
                    "c" -> {
                        col = colIndex(p.getAttributeValue(null, "r") ?: "A1")
                        type = p.getAttributeValue(null, "t") ?: ""
                        cur.clear(); inCell = true
                    }
                    "v" -> inV = true
                    "t" -> if (inCell) inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        var value = when (type) {
                            "s" -> shared.getOrElse(cur.toString().trim().toIntOrNull() ?: -1) { "" }
                            "b" -> if (cur.toString().trim() == "1") "TRUE" else "FALSE"
                            else -> cur.toString()
                        }
                        if ((type == "" || type == "n") && value.isNotBlank()) {
                            value = runCatching { BigDecimal(value.trim()).stripTrailingZeros().toPlainString() }
                                .getOrDefault(value)
                        }
                        if (value.isNotBlank()) grid.getOrPut(row) { TreeMap() }[col] = value.trim()
                        inCell = false
                    }
                }
            }
            ev = p.next()
        }
        return grid
    }
}
