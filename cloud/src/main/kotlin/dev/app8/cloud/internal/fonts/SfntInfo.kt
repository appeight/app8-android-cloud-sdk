package dev.app8.cloud.internal.fonts

/**
 * What the font registry needs from a TrueType / OpenType file (or the first font of a
 * collection): its PostScript name (`name` id 6), family (id 16, else id 1), weight class and
 * italic bit (`OS/2`). The Android counterpart of reading `CGFont.postScriptName`; pure Kotlin so
 * garbage bytes are rejected before anything touches the platform.
 */
internal data class SfntInfo(val postScriptName: String, val family: String?, val weight: Int, val italic: Boolean) {
    companion object {
        fun parse(data: ByteArray): SfntInfo? = runCatching { Reader(data).read() }.getOrNull()
    }

    private class Reader(private val d: ByteArray) {
        private fun u16(at: Int): Int {
            check(at >= 0 && at + 2 <= d.size)
            return ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)
        }

        private fun u32(at: Int): Long {
            check(at >= 0 && at + 4 <= d.size)
            return (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        private fun tag(at: Int): String {
            check(at >= 0 && at + 4 <= d.size)
            return String(d, at, 4, Charsets.ISO_8859_1)
        }

        fun read(): SfntInfo? {
            var base = 0
            if (tag(0) == "ttcf") {
                check(u32(8) >= 1)
                base = u32(12).toInt()
            }
            val version = u32(base)
            check(version == 0x00010000L || tag(base) == "OTTO" || tag(base) == "true" || tag(base) == "typ1")
            val numTables = u16(base + 4)
            check(numTables in 1..512)
            var name: Pair<Int, Int>? = null
            var os2: Pair<Int, Int>? = null
            for (i in 0 until numTables) {
                val rec = base + 12 + i * 16
                val t = tag(rec)
                val offset = u32(rec + 8).toInt()
                val length = u32(rec + 12).toInt()
                check(offset >= 0 && length >= 0 && offset.toLong() + length <= d.size)
                when (t) {
                    "name" -> name = offset to length
                    "OS/2" -> os2 = offset to length
                }
            }
            val (nameOffset, _) = name ?: return null
            val names = readNames(nameOffset)
            val ps = names[6]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val family = (names[16] ?: names[1])?.trim()?.takeIf { it.isNotEmpty() }
            var weight = 400
            var italic = false
            os2?.let { (offset, length) ->
                if (length >= 6) weight = u16(offset + 4).coerceIn(1, 1000)
                if (length >= 64) italic = (u16(offset + 62) and 1) != 0
            }
            return SfntInfo(ps, family, weight, italic)
        }

        /** nameID → best string: Windows/Unicode UTF-16BE first, then Mac Roman. */
        private fun readNames(table: Int): Map<Int, String> {
            val count = u16(table + 2)
            val strings = table + u16(table + 4)
            val best = HashMap<Int, Pair<Int, String>>()
            for (i in 0 until count) {
                val rec = table + 6 + i * 12
                val platform = u16(rec)
                val encoding = u16(rec + 2)
                val nameId = u16(rec + 6)
                if (nameId != 1 && nameId != 6 && nameId != 16) continue
                val length = u16(rec + 8)
                val start = strings + u16(rec + 10)
                if (start < 0 || start + length > d.size) continue
                val (rank, text) = when {
                    platform == 3 && (encoding == 1 || encoding == 10) -> 0 to String(d, start, length, Charsets.UTF_16BE)
                    platform == 0 -> 1 to String(d, start, length, Charsets.UTF_16BE)
                    platform == 1 && encoding == 0 -> 2 to String(d, start, length, Charsets.ISO_8859_1)
                    else -> continue
                }
                val current = best[nameId]
                if (current == null || rank < current.first) best[nameId] = rank to text
            }
            return best.mapValues { it.value.second }
        }
    }
}
