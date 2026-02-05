package com.midasxxi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities

class PlayCinematic : ExtractorApi() {
    override val name = "PlayCinematic"
    override val mainUrl = "https://playcinematic.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT)
        val videoId = when {
            url.contains("/video/") -> url.substringAfter("/video/").substringBefore("/")
            url.contains("/stream/k/") -> url.substringAfter("/stream/k/").substringBefore("/")
            else -> null
        }

        val streamUrl = when {
            url.contains("/stream/k/") -> url
            !videoId.isNullOrBlank() -> "$mainUrl/stream/k/$videoId"
            else -> return
        }

        // ambil redirect ke file asli (mp4/m3u8)
        val resp = app.get(
            streamUrl,
            referer = referer ?: mainUrl,
            headers = headers,
            allowRedirects = false
        )

        val location = resp.headers["Location"] ?: resp.headers["location"] ?: return
        val isM3u8 = location.contains(".m3u8", ignoreCase = true)
        val quality = parseQuality(location)

        if (isM3u8) {
            generateM3u8(
                name,
                location,
                referer = mainUrl,
                headers = headers
            ).forEach(callback)
        } else {
            callback(
                ExtractorLink(
                    name,
                    name,
                    location,
                    mainUrl,
                    quality
                )
            )
        }
    }

    private fun parseQuality(url: String): Int {
        val match = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(url)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
    }
}
