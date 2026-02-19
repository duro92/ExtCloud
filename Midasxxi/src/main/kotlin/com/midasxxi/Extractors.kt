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

    private fun toAbsolute(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) url else "/$url"}"
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
        val directFromTag = pageResp.document
            .selectFirst("video[src], source[src]")
            ?.attr("src")
            ?.trim()

        val streamUrl = when {
            !directFromTag.isNullOrBlank() -> directFromTag
            else -> {
                val unpackedScript = pageResp.document
                    .select("script")
                    .firstOrNull { it.data().contains("eval(function(p,a,c,k,e,d)") }
                    ?.data()
                    ?.let { runCatching { getAndUnpack(it) }.getOrNull() }

                findStreamUrlFromHtml(unpackedScript ?: "") ?: findStreamUrlFromHtml(html)
            }
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
