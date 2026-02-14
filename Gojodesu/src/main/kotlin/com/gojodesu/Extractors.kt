package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.apmap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.httpsify
import org.jsoup.Jsoup
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


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

        val links = document.select("ul#dropdown-server li a")
        for (a in links) {
            loadExtractor(
                base64Decode(a.attr("data-frame")),
                "$mainUrl/",
                subtitleCallback,
                callback
            )
        }
    }
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    private fun hostBase(u: String): String = URI(u).let { "${it.scheme}://${it.host}" }
    private fun hostSlash(u: String): String = hostBase(u) + "/"

    private suspend fun resolveMetaRedirect(startUrl: String, referer: String): String {
        var curUrl = startUrl
        var curRef = referer

        repeat(6) {
            val resp = app.get(curUrl, referer = curRef, allowRedirects = true)
            val html = resp.text
            val ct = (resp.headers["content-type"] ?: "").lowercase()

            // stop kalau sudah playlist/video
            if (curUrl.contains(".m3u8", true) || curUrl.contains(".mp4", true)) return curUrl
            if (!ct.contains("text/html") && !ct.contains("application/xhtml")) return curUrl

            val metaUrl = Regex(
                """http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["'][^"']*url\s*=\s*'?([^"' >]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val linkUrl = Regex(
                """Redirecting\s+to\s+<a\s+href=["']([^"']+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val next = (metaUrl ?: linkUrl)?.trim()
            if (!next.isNullOrBlank() && next != curUrl) {
                curRef = curUrl
                curUrl = httpsify(next)
                return@repeat
            }

            return curUrl
        }
        return curUrl
    }

    private fun findM3u8InText(text: String): String? =
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(text)?.value

    private suspend fun unwrapToMasterM3u8(m3u8Url: String): String {
        return try {
            val resp = app.get(m3u8Url, referer = hostSlash(m3u8Url), allowRedirects = true)
            val body = resp.text

            // kalau wrapper mengandung link master.m3u8 lain, ambil itu
            val inside = Regex("""https?://[^\s#]+/master\.m3u8[^\s#]*""")
                .find(body)?.value

            httpsify(inside ?: m3u8Url)
        } catch (_: Exception) {
            m3u8Url
        }
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageReferer = referer ?: "$mainUrl/"

        // 1) Resolve meta refresh dulu (ini yang hilang di kode kamu)
        val resolvedUrl = resolveMetaRedirect(url, pageReferer)

        // 2) Ambil konten dari URL yang sudah resolved
        val resp = app.get(resolvedUrl, referer = pageReferer, allowRedirects = true)

        // 3) Cari m3u8 dari HTML (atau kalau resolved sudah m3u8)
        val firstM3u8 = when {
            resolvedUrl.contains(".m3u8", true) -> resolvedUrl
            else -> findM3u8InText(resp.text)
        } ?: return null

        // 4) Unwrap ke master final (contoh: cdn1... -> g251.../master.m3u8)
        val finalM3u8 = unwrapToMasterM3u8(httpsify(firstM3u8))

        // 5) Header untuk cegah 2004 (segment hotlink)
        val finalHost = hostBase(finalM3u8)

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = finalM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to "$finalHost/",
                    "Origin" to finalHost
                )
            }
        )
    }
}

