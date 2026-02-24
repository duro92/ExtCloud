package com.midasxxi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink



class Playcinematic : ExtractorApi() {
    override val name = "Playcinematic"
    override val mainUrl = "https://playcinematic.com"
    override val requiresReferer = true

    data class Tracks(
        val file: String,
        val label: String? = null,
        val kind: String? = null
    )

    private fun toAbsolute(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http", true) -> url
            url.startsWith("//") -> "https:$url"
            else -> "$mainUrl${if (url.startsWith("/")) url else "/$url"}"
        }
    }

    private fun findStreamUrlFromHtml(html: String): String? {
        val normalized = html.replace("\\/", "/")
        val patterns = listOf(
            Regex("""["']((?:https?:)?//[^"']*/stream/[^"']+)["']"""),
            Regex("""["'](/stream/[^"']+)["']"""),
            Regex("""(?:file|src)\s*[:=]\s*["']([^"']*/stream/[^"']+)["']""")
        )

        patterns.forEach { regex ->
            val hit = regex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }

    private fun extractSubtitlesFromText(
        htmlOrJs: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seen: MutableSet<String>
    ) {
        val text = htmlOrJs.replace("\\/", "/")

        
        Regex("""<track[^>]+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { m ->
                val src = toAbsolute(m.groupValues[1].trim())
                if (!seen.add(src)) return@forEach

                val tag = m.value
                val label = Regex("""label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)
                val lang = Regex("""srclang=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)

                subtitleCallback(
                    SubtitleFile(
                        getLanguage(label ?: lang ?: "Subtitle"),
                        src
                    )
                )
            }

        
        val tracksArray = Regex(
            """"tracks"\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.groupValues?.getOrNull(1)

        if (!tracksArray.isNullOrBlank()) {
            val json = "[$tracksArray]"
            val list = tryParseJson<List<Tracks>>(json) ?: return

            list.forEach { tr ->
                val file = tr.file.trim()
                if (file.isBlank()) return@forEach

                // filter kind jika ada
                val k = tr.kind?.lowercase()
                if (k != null && k !in listOf("captions", "subtitles")) return@forEach

                val subUrl = toAbsolute(file)
                if (!seen.add(subUrl)) return@forEach

                subtitleCallback(
                    SubtitleFile(
                        getLanguage(tr.label ?: "Subtitle"),
                        subUrl
                    )
                )
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = referer ?: "$mainUrl/"
        val pageResp = app.get(
            url = url,
            referer = pageRef,
            headers = mapOf("Referer" to pageRef)
        )

        val html = pageResp.text

        val packed = pageResp.document
            .select("script")
            .firstOrNull { it.data().contains("eval(function(p,a,c,k,e,d)") }
            ?.data()

        val unpackedScript = packed?.let { runCatching { getAndUnpack(it) }.getOrNull() }

       
        val seenSubs = mutableSetOf<String>()

       
        if (!unpackedScript.isNullOrBlank()) {
            extractSubtitlesFromText(unpackedScript, subtitleCallback, seenSubs)
        }
        extractSubtitlesFromText(html, subtitleCallback, seenSubs)

        val directFromTag = pageResp.document
            .selectFirst("video[src], source[src]")
            ?.attr("src")
            ?.trim()

        val streamUrl = when {
            !directFromTag.isNullOrBlank() -> directFromTag
            else -> findStreamUrlFromHtml(unpackedScript ?: "") ?: findStreamUrlFromHtml(html)
        } ?: return

        val absoluteUrl = toAbsolute(streamUrl)
        val type = if (absoluteUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = absoluteUrl,
                type = type
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
            }
        )
    }
}

private fun getLanguage(str: String): String {
    return when {
        str.contains("indonesia", true) || str.contains("bahasa", true) || str.equals("id", true) -> "Indonesian"
        str.contains("english", true) || str.equals("en", true) -> "English"
        else -> str
    }
}
