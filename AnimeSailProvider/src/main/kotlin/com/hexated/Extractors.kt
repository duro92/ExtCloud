package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class MixDropBz : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://m1xdrop.bz"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/e/$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/f/", "/e/")
        val response = app.get(
            embedUrl,
            referer = referer ?: "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT)
        )

        val scriptChunks = linkedSetOf<String>()
        scriptChunks.add(response.text)
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isBlank()) return@forEach
            scriptChunks.add(data)
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(data) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { scriptChunks.add(it) }
            }
        }

        val streamRegexes = listOf(
            Regex("""(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:MDCore\.)?furl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )

        val streamUrl = scriptChunks.firstNotNullOfOrNull { blob ->
            streamRegexes.firstNotNullOfOrNull { regex ->
                regex.find(blob)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != " " }
            }
        }?.let { raw ->
            when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                raw.startsWith("//") -> "https:$raw"
                else -> null
            }
        } ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = INFER_TYPE
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl
                )
            }
        )
    }
}
