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

    private fun hostBase(u: String): String =
        URI(u).let { "${it.scheme}://${it.host}" }

    private fun hostSlash(u: String): String =
        hostBase(u) + "/"

    private suspend fun resolveRedirect(url: String, referer: String): String {
        var current = url
        var ref = referer

        repeat(6) {
            val resp = app.get(current, referer = ref, allowRedirects = true)
            val html = resp.text

            if (current.contains(".m3u8", true)) return current

            val meta = Regex(
                """http-equiv\s*=\s*["']refresh["'][^>]*url\s*=\s*'?([^"' >]+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val link = Regex(
                """Redirecting\s+to\s+<a\s+href=["']([^"']+)""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val next = meta ?: link ?: return current

            ref = current
            current = httpsify(next)
        }

        return current
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {

        val pageReferer = referer ?: "$mainUrl/"

        val resolved = resolveRedirect(url, pageReferer)

        val firstResp = app.get(resolved, referer = pageReferer, allowRedirects = true)
        val html = firstResp.text

        val wrapperM3u8 = if (resolved.contains(".m3u8", true)) {
            resolved
        } else {
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                .find(html)
                ?.value
                ?: return null
        }

        val wrapperRef = hostSlash(wrapperM3u8)

        val wrapperResp = app.get(
            wrapperM3u8,
            referer = wrapperRef,
            allowRedirects = true
        )

        val wrapperBody = wrapperResp.text

        val masterM3u8 = Regex("""https?://[^\s#]+/master\.m3u8[^\s#]*""")
            .find(wrapperBody)
            ?.value
            ?: wrapperM3u8

        val finalUrl = httpsify(masterM3u8)
        val finalHost = hostBase(finalUrl)

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$finalHost/"
                this.headers = mapOf(
                    "Referer" to "$finalHost/",
                    "Origin" to finalHost
                )
            }
        )
    }
}


