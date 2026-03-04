package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.Hxfile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URL

open class Gdplayer : ExtractorApi() {
    override val name = "Gdplayer"
    override val mainUrl = "https://gdplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).document
        val script = res.selectFirst("script:containsData(player = \"\")")?.data()
        val kaken = script?.substringAfter("kaken = \"")?.substringBefore("\"")

        val json = app.get(
            "$mainUrl/api/?${kaken ?: return}=&_=${APIHolder.unixTimeMS}",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).parsedSafe<Response>()

        json?.sources?.map {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    it.file ?: return@map,
                    INFER_TYPE
                ) {
                    this.quality = getQuality(json.title)
                }
            )
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    data class Response(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sources") val sources: ArrayList<Sources>? = null,
    ) {
        data class Sources(
            @JsonProperty("file") val file: String? = null,
            @JsonProperty("type") val type: String? = null,
        )
    }

}

class Nontonanimeid : Hxfile() {
    override val name = "Nontonanimeid"
    override val mainUrl = "https://nontonanimeid.com"
    override val requiresReferer = true
}

class EmbedKotakAnimeid : Hxfile() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val hls = tryResolveKotakHlsFromPage(url, referer)
        if (hls.isNotEmpty()) return hls
        return super.getUrl(url, referer)?.filterNot { it.isGoogleVideo() }
    }
}

class Kotaksb : Hxfile() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.fun"
    override val requiresReferer = true
}

class KotakAnimeidCom : Hxfile() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val hls = tryResolveKotakHlsFromPage(url, referer)
        if (hls.isNotEmpty()) return hls
        return super.getUrl(url, referer)?.filterNot { it.isGoogleVideo() }
    }
}

class KotakAnimeidLink : Hxfile() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.link"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val hls = tryResolveKotakHlsFromPage(url, referer)
        if (hls.isNotEmpty()) return hls
        return super.getUrl(url, referer)?.filterNot { it.isGoogleVideo() }
    }
}

class Vidhidepre : Filesim() {
    override val name = "Vidhidepre"
    override var mainUrl = "https://vidhidepre.com"
}

class Rpmvip : VidStack() {
    override var name = "Rpmvip"
    override var mainUrl = "https://s1.rpmvip.com"
    override var requiresReferer = true
}

private suspend fun tryResolveKotakHlsFromPage(
    url: String,
    referer: String?
): List<ExtractorLink> {
    val html = app.get(url, referer = referer).text
    val unescaped = html.replace("\\/", "/")
    val urls = LinkedHashSet<String>()
    Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
        .findAll(unescaped)
        .forEach { urls.add(it.value) }
    Regex("""//[^\s"'\\]+\.m3u8[^\s"'\\]*""", RegexOption.IGNORE_CASE)
        .findAll(unescaped)
        .forEach { urls.add(normalizeUrl(it.value)) }

    if (urls.isEmpty()) return emptyList()

    val origin = originOf(url)
    return urls.map { link ->
        newExtractorLink(
            "KotakAnimeid",
            "KotakAnimeid",
            link,
            INFER_TYPE
        ) {
            this.referer = url
            this.headers = (headers ?: emptyMap()) + mapOf(
                "Referer" to url,
                "Origin" to origin,
                "User-Agent" to USER_AGENT
            )
        }
    }
}

private fun normalizeUrl(url: String): String {
    return if (url.startsWith("//")) "https:$url" else url
}

private fun originOf(url: String): String {
    val parsed = URL(url)
    return "${parsed.protocol}://${parsed.host}"
}

private fun ExtractorLink.isGoogleVideo(): Boolean {
    return url.contains("googlevideo.com/videoplayback") || url.contains("source=blogger")
}
