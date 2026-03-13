package com.pmsm

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class DhtprePmsm : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}

class NetuPmsm : VidhideExtractor() {
    override var mainUrl = "https://netu.msmbot.club"
}

class Playerxupns : VidStack() {
    override var name = "Playerxupns"
    override var mainUrl = "https://playerx.upns.live"
    override var requiresReferer = true
}

class Playerxp2p : VidStack() {
    override var name = "Playerxp2p"
    override var mainUrl = "https://playerx.p2pstream.online"
    override var requiresReferer = true
}

class Playerxseek : VidStack() {
    override var name = "Playerxseek"
    override var mainUrl = "https://playerx.seekplays.online"
    override var requiresReferer = true
}

class Playerxrpms : VidStack() {
    override var name = "Playerxrpms"
    override var mainUrl = "https://playerx.rpmstream.online"
    override var requiresReferer = true
}

class Player4me : VidStack() {
    override var name = "Player4me"
    override var mainUrl = "https://playerx.player4me.online"
    override var requiresReferer = true
}

class Ezplayer : VidStack() {
    override var name = "Ezplayer"
    override var mainUrl = "https://playerx.ezplayer.stream"
    override var requiresReferer = true
}

class YandexcdnPmsm : ExtractorApi() {
    override val name = "Yandexcdn"
    override val mainUrl = "https://yandexcdn.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = referer ?: "$mainUrl/"
        val pages = linkedSetOf(url)

        val firstPage = runCatching {
            app.get(url, referer = pageRef, headers = mapOf("Referer" to pageRef)).text
        }.getOrNull() ?: return

        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(firstPage)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!iframeSrc.isNullOrBlank()) {
            resolveUrl(url, iframeSrc)?.let { pages.add(it) }
        }

        val streams = mutableSetOf<String>()
        pages.forEach { pageUrl ->
            val page = if (pageUrl == url) {
                firstPage
            } else {
                runCatching {
                    app.get(pageUrl, referer = pageRef, headers = mapOf("Referer" to pageRef)).text
                }.getOrNull()
            } ?: return@forEach

            listOf(
                Regex("""(?:https?:)?//[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE),
                Regex("""(?:https?:)?//[^\s"'\\]+\.mp4(?:\?[^\s"'\\]*)?""", RegexOption.IGNORE_CASE)
            ).forEach { pattern ->
                pattern.findAll(page).forEach { match ->
                    val normalized = resolveUrl(pageUrl, match.value)
                        ?.replace("\\/", "/")
                        ?.trim()
                    if (!normalized.isNullOrBlank()) streams.add(normalized)
                }
            }
        }

        val yandexReferer = pages.firstOrNull { it.contains("/e/") } ?: "$mainUrl/"
        streams.forEach { stream ->
            val type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = type
                ) {
                    this.referer = yandexReferer
                    this.headers = mapOf(
                        "Referer" to yandexReferer,
                        "Origin" to mainUrl
                    )
                }
            )
        }
    }

    private fun resolveUrl(base: String, target: String): String? {
        val cleaned = target.replace("\\/", "/").trim()
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned
        if (cleaned.startsWith("//")) return "https:$cleaned"
        return runCatching { URI(base).resolve(cleaned).toString() }.getOrNull()
    }
}
