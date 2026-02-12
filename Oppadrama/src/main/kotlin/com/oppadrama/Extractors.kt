package com.oppadrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Qualities



class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val script = response.document.selectXpath(
            "//script[contains(text(),'urlPlay') or contains(text(),'sources') or contains(text(),'file')]"
        ).joinToString("\n") { it.html() } + "\n" + response.text

        fun normalizeUrl(raw: String): String {
            return raw
                .replace("\\u002F", "/")
                .replace("\\u003A", ":")
                .replace("\\/", "/")
                .trim()
                .let { if (it.startsWith("//")) "https:$it" else it }
        }

        val directUrl = listOf(
            Regex("urlPlay\\s*=\\s*['\"]([^'\"]+)['\"]").find(script)?.groupValues?.getOrNull(1),
            Regex("file\\s*:\\s*\"([^\"]+)\"").find(script)?.groupValues?.getOrNull(1),
            Regex("file\\s*:\\s*'([^']+)'").find(script)?.groupValues?.getOrNull(1),
            Regex("src\\s*:\\s*\"([^\"]+)\"").find(script)?.groupValues?.getOrNull(1),
            Regex("src\\s*:\\s*'([^']+)'").find(script)?.groupValues?.getOrNull(1),
        ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it) }

        if (directUrl.isNullOrBlank()) return null

        val type = if (directUrl.contains(".m3u8", true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = directUrl,
                type = type
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityText = app.get(url).documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)
            val response = app.get("$url/download", referer = url, allowRedirects = false)
            val redirectUrl = response.headers["hx-redirect"] ?: ""

            if (redirectUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        "BuzzServer",
                        "BuzzServer",
                        redirectUrl,
                    ) {
                        this.quality = quality
                    }
                )
            } else {
                Log.w("BuzzServer", "No redirect URL found in headers.")
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Exception occurred: ${e.message}")
        }
    }
}
