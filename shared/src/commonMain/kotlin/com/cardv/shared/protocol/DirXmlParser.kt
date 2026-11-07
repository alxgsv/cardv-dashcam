package com.cardv.shared.protocol

import com.cardv.shared.model.Clip
import kotlinx.datetime.LocalDateTime

/** One page of `action=dir` output. */
data class DirListing(
    /** Root element as echoed by the camera, usually the requested property (`DCIM`, `SOS`, …). */
    val rootTag: String,
    /** Files in this page, in the camera's order. */
    val clips: List<Clip>,
    /** `<amount>` from the response: number of files in this page, not the whole folder. */
    val amount: Int,
)

/**
 * Parser for the small XML document returned by `action=dir`.
 *
 * The document is flat and fixed-shape, so a dependency-free parser is enough and keeps the shared
 * module small. Unknown child tags and attributes are ignored.
 */
object DirXmlParser {
    private val FILE_BLOCK = Regex("""<file\b[^>]*>(.*?)</file>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ROOT_TAG = Regex("""<([A-Za-z_][A-Za-z0-9_.:-]*)\b""")
    private val AMOUNT = Regex("""<amount\b[^>]*>(.*?)</amount>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val FORMAT = Regex("""<format\b([^>]*)>(.*?)</format>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val FORMAT_EMPTY = Regex("""<format\b([^>]*)/\s*>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("""([A-Za-z_][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun parse(xml: String): DirListing {
        val cleaned = xml
            .removePrefix("\uFEFF")
            .replace(Regex("""<\?.*?\?>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()

        if (cleaned.isEmpty()) throw MalformedResponseException("empty directory response")
        if (!cleaned.startsWith("<")) {
            throw MalformedResponseException("expected XML directory listing, got: ${cleaned.take(80)}")
        }

        val rootTag = ROOT_TAG.find(cleaned)?.groupValues?.get(1)
            ?: throw MalformedResponseException("directory response has no root element")

        val clips = FILE_BLOCK.findAll(cleaned).mapNotNull { match ->
            parseClip(match.groupValues[1])
        }.toList()

        val amount = AMOUNT.find(cleaned)?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: clips.size
        return DirListing(rootTag = rootTag, clips = clips, amount = amount)
    }

    private fun parseClip(block: String): Clip? {
        val path = tagText(block, "name") ?: return null
        val formatMatch = FORMAT.find(block)
        val formatEmptyMatch = if (formatMatch == null) FORMAT_EMPTY.find(block) else null
        val formatAttributes = parseAttributes(
            formatMatch?.groupValues?.get(1) ?: formatEmptyMatch?.groupValues?.get(1) ?: "",
        )
        val formatText = formatMatch?.groupValues?.get(2)?.let(::decodeEntities)?.trim()

        val timeText = tagText(block, "time")
        return Clip(
            path = path.trim(),
            sizeBytes = tagText(block, "size")?.toLongOrNull() ?: 0L,
            timestamp = timeText?.let(CameraDateTimes::parse),
            attribute = tagText(block, "attr"),
            format = formatText?.takeIf { it.isNotEmpty() },
            resolution = formatAttributes["size"],
            fps = formatAttributes["fps"]?.toIntOrNull(),
            durationSeconds = formatAttributes["time"]?.toDoubleOrNull(),
        )
    }

    private fun tagText(block: String, name: String): String? {
        val regex = Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return regex.find(block)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
    }

    private fun parseAttributes(text: String): Map<String, String> =
        ATTRIBUTE.findAll(text).associate { match ->
            match.groupValues[1].lowercase() to decodeEntities(
                match.groupValues[2].ifEmpty { match.groupValues[3] },
            )
        }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("""&#x([0-9A-Fa-f]+);""")) { match ->
                match.groupValues[1].toIntOrNull(16)?.let { it.toChar().toString() } ?: match.value
            }
            .replace(Regex("""&#([0-9]+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: match.value
            }
            .replace("&amp;", "&")
    }
}

/** Camera time helper for callers that already hold a raw value. */
internal fun parseCameraDateTime(value: String): LocalDateTime? = CameraDateTimes.parse(value)
