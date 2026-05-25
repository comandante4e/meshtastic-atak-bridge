package com.dkashev.cotbridge.cot

import java.util.Locale

object CotXml {

    fun extractUid(xml: String): String? = extractEventAttr(xml, "uid")
    fun extractType(xml: String): String? = extractEventAttr(xml, "type")
    fun extractTime(xml: String): String? = extractEventAttr(xml, "time")

    /** Extracts attribute value from the opening `<event ...>` tag only, ignoring nested elements. */
    fun extractEventAttr(xml: String, name: String): String? {
        val open = xml.indexOf("<event")
        if (open < 0) return null
        val close = xml.indexOf('>', open)
        if (close < 0) return null
        return attr(xml.substring(open, close), name)
    }

    private fun attr(head: String, name: String): String? {
        val pattern = """\s${Regex.escape(name)}\s*=\s*"([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(head)?.groupValues?.getOrNull(1)
    }
}

/** Buffer that extracts complete `<event ...>...</event>` chunks from a byte stream. */
class CotStreamSplitter {

    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val events = mutableListOf<String>()
        while (true) {
            val start = buffer.indexOf("<event", ignoreCase = false)
            if (start < 0) {
                buffer.clear()
                break
            }
            if (start > 0) buffer.delete(0, start)

            val selfCloseAt = findSelfClose(buffer)
            val endTagAt = buffer.indexOf("</event>", ignoreCase = false)

            val end = when {
                selfCloseAt >= 0 && (endTagAt < 0 || selfCloseAt < endTagAt) -> selfCloseAt + 2
                endTagAt >= 0 -> endTagAt + "</event>".length
                else -> -1
            }
            if (end < 0) break

            events += buffer.substring(0, end)
            buffer.delete(0, end)
        }
        return events
    }

    private fun findSelfClose(sb: StringBuilder): Int {
        val firstClose = sb.indexOf('>')
        if (firstClose < 0) return -1
        if (firstClose >= 1 && sb[firstClose - 1] == '/') return firstClose - 1
        return -1
    }
}

/** Bounded LRU of recent CoT UIDs, to suppress echo between sources. */
class LoopDetector(private val capacity: Int = 256) {
    private val set = linkedSetOf<String>()

    @Synchronized
    fun markAndCheck(uid: String): Boolean {
        if (set.contains(uid)) return true
        if (set.size >= capacity) set.iterator().run { next(); remove() }
        set.add(uid)
        return false
    }

    @Synchronized
    fun reset() = set.clear()
}

/** Minimal default PLI XML (used only as a heartbeat if needed; ATAK normally emits its own). */
fun heartbeatPli(uid: String, callsign: String, lat: Double, lon: Double): String {
    val now = java.time.Instant.now().toString()
    val stale = java.time.Instant.now().plusSeconds(75).toString()
    return """<event version="2.0" uid="$uid" type="a-f-G-U-C" time="$now" start="$now" stale="$stale" how="m-g">""" +
        """<point lat="${"%.7f".format(Locale.US, lat)}" lon="${"%.7f".format(Locale.US, lon)}" hae="0" ce="9999999" le="9999999"/>""" +
        """<detail><contact callsign="$callsign"/><__group name="Cyan" role="Team Member"/></detail>""" +
        """</event>"""
}
