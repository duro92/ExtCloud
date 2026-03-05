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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
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

open class KotakAnimeidBase : ExtractorApi() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.link"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = referer)
        val html = response.text
        val document = response.document
        val referer = preferredKotakReferer(url)
        val refererOrigin = originOf(referer)

        val sources = mutableListOf<ExtractorLink>()
        sources.addAll(extractStreamSources(html, referer, refererOrigin))
        sources.addAll(extractScriptSources(document, referer, refererOrigin))

        return sources.distinctBy { it.url }
    }
}

class EmbedKotakAnimeid : KotakAnimeidBase() {
    override val name = "EmbedKotakAnimeid"
    override val mainUrl = "https://embed2.kotakanimeid.com"
}

class Kotaksb : Hxfile() {
    override val name = "Kotaksb"
    override val mainUrl = "https://kotaksb.fun"
    override val requiresReferer = true
}

class KotakAnimeidCom : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
}

class KotakAnimeidLink : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.link"
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

private suspend fun extractStreamSources(
    html: String,
    referer: String,
    origin: String
): List<ExtractorLink> {
    val urls = collectStreamUrls(html)
    return buildLinksFromUrls(urls, referer, origin)
}

private suspend fun extractScriptSources(
    document: org.jsoup.nodes.Document,
    referer: String,
    origin: String
): List<ExtractorLink> {
    val sources = mutableListOf<ExtractorLink>()
    document.select("script").forEach { script ->
        val data = script.data()
        if (data.contains("eval(function(p,a,c,k,e,d)")) {
            val unpacked = getAndUnpack(data)
            sources.addAll(buildLinksFromUrls(collectStreamUrls(unpacked), referer, origin))
            val src = unpacked.substringAfter("sources:[").substringBefore("]")
            tryParseJson<List<ResponseSource>>("[$src]")?.forEach { source ->
                sources.add(
                    newExtractorLink(
                        "KotakAnimeid",
                        "KotakAnimeid",
                        source.file,
                        INFER_TYPE
                    ) {
                        this.referer = referer
                        this.headers = (headers ?: emptyMap()) + mapOf(
                            "Referer" to referer,
                            "Origin" to origin,
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = getQualityFromName(source.label)
                    }
                )
            }
        } else if (data.contains("\"sources\":[")) {
            sources.addAll(buildLinksFromUrls(collectStreamUrls(data), referer, origin))
            val src = data.substringAfter("\"sources\":[").substringBefore("]")
            tryParseJson<List<ResponseSource>>("[$src]")?.forEach { source ->
                sources.add(
                    newExtractorLink(
                        "KotakAnimeid",
                        "KotakAnimeid",
                        source.file,
                        INFER_TYPE
                    ) {
                        this.referer = referer
                        this.headers = (headers ?: emptyMap()) + mapOf(
                            "Referer" to referer,
                            "Origin" to origin,
                            "User-Agent" to USER_AGENT
                        )
                        this.quality = when {
                            source.label?.contains("HD") == true -> Qualities.P720.value
                            source.label?.contains("SD") == true -> Qualities.P480.value
                            else -> getQualityFromName(source.label)
                        }
                    }
                )
            }
        } else {
            sources.addAll(buildLinksFromUrls(collectStreamUrls(data), referer, origin))
        }
    }
    return sources
}

private fun collectStreamUrls(text: String): List<String> {
    val cleaned = text.replace("\\\\/", "/").replace("\\/", "/")
    val urls = LinkedHashSet<String>()
    val pattern =
        Regex("""https?://[^\s"'\\]+?\.(m3u8|mp4|mkv|webm)(\?[^\s"'\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    pattern.findAll(cleaned).forEach { match ->
        urls.add(match.value)
    }
    val patternRelative =
        Regex("""//[^\s"'\\]+?\.(m3u8|mp4|mkv|webm)(\?[^\s"'\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    patternRelative.findAll(cleaned).forEach { match ->
        urls.add(normalizeUrl(match.value))
    }
    return urls.toList()
}

private suspend fun buildLinksFromUrls(
    urls: List<String>,
    referer: String,
    origin: String
): List<ExtractorLink> {
    if (urls.isEmpty()) return emptyList()
    return urls
        .map { link ->
            newExtractorLink(
                "KotakAnimeid",
                "KotakAnimeid",
                link,
                INFER_TYPE
            ) {
                this.referer = referer
                this.headers = (headers ?: emptyMap()) + mapOf(
                    "Referer" to referer,
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

private fun preferredKotakReferer(url: String): String {
    val parsed = URL(url)
    return if (parsed.host.endsWith("kotakanimeid.link")) {
        "${parsed.protocol}://s1.kotakanimeid.link/"
    } else {
        "${parsed.protocol}://${parsed.host}/"
    }
}

private data class ResponseSource(
    @JsonProperty("file") val file: String,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("label") val label: String? = null
)
