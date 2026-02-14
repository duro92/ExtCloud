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

        // Embed servers (data-frame)
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
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
    val links = mutableListOf<ExtractorLink>()
    val firstReferer = referer ?: "$mainUrl/"

    // Step 1: resolve redirect chain
    val resp = app.get(url, referer = firstReferer, allowRedirects = true)
    var finalUrl = resp.url

    // Step 2: kalau response adalah m3u8 langsung
    val contentType = resp.headers["content-type"] ?: ""
    if (contentType.contains("mpegurl") || finalUrl.contains(".m3u8")) {
        links.add(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = baseOf(finalUrl)
                this.quality = Qualities.Unknown.value
            }
        )
        return links
    }

    val body = resp.text

    // Step 3: cari m3u8 di dalam HTML / JS
    val m3u8Regex = Regex("""https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*""")
    val m3u8 = m3u8Regex.find(body)?.value

    if (m3u8 != null) {
        links.add(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = baseOf(m3u8)
                this.quality = Qualities.Unknown.value
            }
        )
        return links
    }

    return null
    }

}

private fun baseOf(u: String): String =
    URI(u).let { "${it.scheme}://${it.host}/" }
