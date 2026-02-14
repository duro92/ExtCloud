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

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun hostBase(url: String): String {
        val u = httpsify(url)
        val m = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(u)
        return m?.groupValues?.get(1) ?: mainUrl
    }

    private fun hostSlash(url: String): String = hostBase(url).trimEnd('/') + "/"

    private fun absUrl(base: String, u: String): String {
        val x = u.trim()
        if (x.startsWith("http", true)) return x
        return base.trimEnd('/') + "/" + x.trimStart('/')
    }

    private suspend fun fetchText(url: String, referer: String): String {
        return app.get(
            url,
            referer = referer,
            allowRedirects = true,
            headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "*/*"
            )
        ).text
    }

    private suspend fun resolveRedirect(url: String, referer: String): String {
        var current = httpsify(url)
        var ref = referer

        repeat(6) {
            val resp = app.get(
                current,
                referer = ref,
                allowRedirects = true,
                headers = mapOf("User-Agent" to UA)
            )
            val html = resp.text

            if (current.contains(".m3u8", true) || current.contains(".mp4", true)) return current

            val meta = Regex(
                """http-equiv\s*=\s*["']refresh["'][^>]*url\s*=\s*'?([^"' >]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val link = Regex(
                """Redirecting\s+to\s+<a\s+href=["']([^"']+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val next = (meta ?: link)?.trim() ?: return current

            ref = current
            current = httpsify(next)
        }

        return current
    }

    private fun findFirstM3u8(text: String): String? =
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(text)?.value

    private fun findMasterInWrapper(wrapperBody: String): String? =
        Regex("""https?://[^\s#]+/master\.m3u8[^\s#]*""").find(wrapperBody)?.value

    private fun pickBestVariant(masterBody: String): String? {
        // Ambil variant dengan BANDWIDTH terbesar
        val lines = masterBody.lines()
        var bestUrl: String? = null
        var bestBw = -1

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF", true)) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val next = lines.getOrNull(i + 1)?.trim()
                if (!next.isNullOrBlank() && !next.startsWith("#")) {
                    if (bw > bestBw) {
                        bestBw = bw
                        bestUrl = next
                    }
                }
                i += 2
                continue
            }
            i++
        }
        return bestUrl
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        
        val upstreamRef = (referer ?: "$mainUrl/").trim()

        val resolved = resolveRedirect(url, upstreamRef)

        val html = if (resolved.contains(".m3u8", true)) "" else fetchText(resolved, upstreamRef)

        val wrapperM3u8 = (if (resolved.contains(".m3u8", true)) resolved else findFirstM3u8(html))
            ?.let { httpsify(it) } ?: return null

        val wrapperRef = hostSlash(wrapperM3u8)
        val wrapperBody = fetchText(wrapperM3u8, wrapperRef)

        val masterUrl = (findMasterInWrapper(wrapperBody) ?: wrapperM3u8).let { httpsify(it) }
        val masterRef = wrapperRef

        val masterBody = fetchText(masterUrl, masterRef)
        if (!masterBody.trimStart().startsWith("#EXTM3U")) return null

        val variantRel = pickBestVariant(masterBody) ?: return null
        val variantUrl = httpsify(absUrl(hostSlash(masterUrl), variantRel))

        val origin = hostBase(variantUrl)

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = variantUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = upstreamRef
                this.headers = mapOf(
                    "Referer" to upstreamRef,
                    "Origin" to origin,
                    "User-Agent" to UA,
                    "Accept" to "*/*"
                )
            }
        )
    }
}


