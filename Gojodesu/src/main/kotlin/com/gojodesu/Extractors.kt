package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import java.net.URI


open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        // Embed servers (data-frame)
        val links = document.select("ul#dropdown-server li a")
        for (a in links) {
            val decoded = base64Decode(a.attr("data-frame")).trim()
            val iframe = Jsoup.parse(decoded).selectFirst("iframe")
            val rawSrc = when {
                iframe?.attr("src")?.isNotBlank() == true -> iframe.attr("src")
                iframe?.attr("data-src")?.isNotBlank() == true -> iframe.attr("data-src")
                else -> decoded
            }.replace("&amp;", "&").trim()

            val src = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
            if (src.startsWith("http")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }

        // Download buttons on Kotakajaib page
        val downloadLinks = document.select(
            "a:contains(Download), a:contains(download), span.textdownload"
        ).mapNotNull { el ->
            if (el.tagName() == "span") el.parent()?.attr("href") else el.attr("href")
        }.mapNotNull { it.takeIf { link -> link.isNotBlank() } }

        downloadLinks.forEach { link ->
            val fixed = link
                .replace("&amp;", "&")
                .takeIf { it.isNotBlank() } ?: return@forEach

            // Jangan proses shortlink ouo
            if (fixed.contains("ouo.io") || fixed.contains("ouo.press")) return@forEach

            // Pixeldrain direct (u/ID atau file/ID)
            if (fixed.contains("pixeldrain.com/")) {
                val fileId = when {
                    fixed.contains("/u/") -> fixed.substringAfter("/u/").substringBefore("?").substringBefore("/")
                    fixed.contains("/file/") -> fixed.substringAfter("/file/").substringBefore("?").substringBefore("/")
                    else -> ""
                }
                if (fileId.isNotBlank()) {
                    val apiUrl = "https://pixeldrain.com/api/file/$fileId"
                    loadExtractor(apiUrl, "https://pixeldrain.com/", subtitleCallback, callback)
                    return@forEach
                }
            }

            loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)

            // Jika link mengarah ke endpoint API Kotakajaib, coba ambil url langsung
            if (fixed.contains("/api/file/") && fixed.contains("/download")) {
                val apiResp = app.get(fixed, referer = "$mainUrl/").text
                Regex("https?://[^\"'\\s]+").findAll(apiResp).forEach { m ->
                    val u = m.value
                    if (!u.contains("ouo.io") && !u.contains("ouo.press")) {
                        loadExtractor(u, "$mainUrl/", subtitleCallback, callback)
                    }
                }

                val parsed = runCatching { tryParseJson<Map<String, Any>>(apiResp) }.getOrNull()
                parsed?.values?.forEach { v ->
                    val s = v?.toString() ?: return@forEach
                    if (s.startsWith("http") && !s.contains("ouo.io") && !s.contains("ouo.press")) {
                        loadExtractor(s, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }
    }
}



open class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    // Host in iframe biasanya emturbovid.com (kemudian bisa redirect ke turbovidhls.com).
    override var mainUrl = "https://emturbovid.com"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val script = response.document.selectXpath(
            "//script[contains(text(),'urlPlay') or contains(text(),'sources') or contains(text(),'file')]"
        ).joinToString("\n") { it.html() } + "\n" + response.text

        fun normalizeUrl(raw: String): String {
            val clean = raw
                .replace("\\u002F", "/")
                .replace("\\u003A", ":")
                .replace("\\/", "/")
                .trim()

            return when {
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("http://") || clean.startsWith("https://") -> clean
                clean.startsWith("/") -> runCatching {
                    val base = URI(response.url)
                    "${base.scheme}://${base.host}$clean"
                }.getOrElse { clean }
                else -> runCatching { URI(response.url).resolve(clean).toString() }.getOrDefault(clean)
            }
        }

        fun isLikelyMediaUrl(raw: String): Boolean {
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) return false
            val lowered = raw.lowercase()
            if (lowered.contains(".js") || lowered.contains(".css")) return false
            if (lowered.contains(".png") || lowered.contains(".jpg") || lowered.contains(".jpeg") || lowered.contains(".gif") || lowered.contains(".svg") || lowered.contains(".ico")) return false
            return lowered.contains(".m3u8") ||
                lowered.contains(".mp4") ||
                lowered.contains(".mkv") ||
                lowered.contains(".webm") ||
                lowered.contains("/uploads/") ||
                lowered.contains("/playlist") ||
                lowered.contains("/hls/")
        }

        fun score(raw: String): Int {
            val lowered = raw.lowercase()
            return when {
                lowered.contains(".m3u8") -> 4
                lowered.contains(".mp4") -> 3
                lowered.contains(".mkv") || lowered.contains(".webm") -> 2
                else -> 1
            }
        }

        val patterns = listOf(
            Regex("urlPlay\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            Regex("file\\s*:\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            Regex("\"file\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE),
            Regex("'file'\\s*:\\s*'([^']+)'", RegexOption.IGNORE_CASE),
        )

        val directUrl = patterns
            .flatMap { regex -> regex.findAll(script).map { it.groupValues[1] }.toList() }
            .map { normalizeUrl(it) }
            .filter { isLikelyMediaUrl(it) }
            .distinct()
            .sortedByDescending { score(it) }
            .firstOrNull()

        if (directUrl.isNullOrBlank()) return null

        val refererHeader = runCatching {
            val uri = URI(response.url)
            "${uri.scheme}://${uri.host}/"
        }.getOrElse { referer ?: "$mainUrl/" }

        val type = if (directUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        // Some hosts behave poorly with okhttp UA + range requests; force a browser UA.
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
        )

        return listOf(
            ExtractorLink(
                name,
                name,
                directUrl,
                refererHeader,
                Qualities.Unknown.value,
                type,
                headers,
                ""
            )
        )
    }
}

class Turbovidhls : Emturbovid() {
    override var name = "Turbovidhls"
    override var mainUrl = "https://turbovidhls.com"
}
