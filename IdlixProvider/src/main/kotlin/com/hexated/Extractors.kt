package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class IdlixPlayer : ExtractorApi() {

    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Pakai page URL asli agar token/r param sinkron dengan player bawaan.
        val pageRef = if (url.contains("/video/")) url.substringBefore("#") else "$mainUrl/"

        // Ambil halaman embed (cookie awal + subtitle)
        val document = app.get(
            url = url,
            referer = referer ?: "$mainUrl/",
            headers = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive"
            )
        ).document

        // Ambil video ID (support /video/xxxx dan data=xxxx)
        val videoId = when {
            url.contains("/video/") -> url.substringAfterLast("/")
            url.contains("data=") -> url.substringAfter("data=").substringBefore("&")
            else -> url.substringAfterLast("/")
        }.trim()

        if (videoId.isBlank()) return

        // Request token HLS (INI SUMBER TOKEN)
        val response = app.post(
            url = "$mainUrl/player/index.php?do=getVideo&data=$videoId",
            data = mapOf(
                "hash" to videoId,
                "r" to pageRef
            ),
            referer = pageRef,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "User-Agent" to UA,
                "Accept" to "*/*",
                "Connection" to "keep-alive"
            )
        ).parsed<ResponseSource>()

        // WAJIB pakai securedLink (lebih stabil dari videoSource)
        val m3u8Url = response.securedLink?.takeIf { it.isNotBlank() } ?: return
        val streamHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to pageRef,
            "User-Agent" to UA,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Connection" to "keep-alive"
        )
        callback.invoke(
            newExtractorLink(
                name = "Jenius AUTO",
                source = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = pageRef
                this.headers = streamHeaders
            }
        )

        // ================= SUBTITLE =================
        document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("eval(function")) {
                val unpacked = getAndUnpack(data)
                val subData = unpacked
                    .substringAfter("\"tracks\":[")
                    .substringBefore("],")

                tryParseJson<List<Tracks>>("[$subData]")?.forEach { sub ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            getLanguage(sub.label ?: ""),
                            sub.file
                        )
                    )
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    // ================= RESPONSE MODEL =================
    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("securedLink") val securedLink: String?
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?
    )
}
