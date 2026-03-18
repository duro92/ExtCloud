package com.azmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MyvidplayAz : DoodLaExtractor() {
    override var name = "MyVidPlay"
    override var mainUrl = "https://myvidplay.com"
}

class HqqAz : VidhideExtractor() {
    override var name = "HQQ"
    override var mainUrl = "https://hqq.ac"
}

class VidsrcXyzAz : ExtractorApi() {
    override val name = "VidSrc"
    override val mainUrl = "https://vidsrc.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedResponse = app.get(url, referer = referer)
        val rcpUrl =
            extractCloudnestraPath(
                embedResponse.document,
                """<iframe[^>]+id=["']player_iframe["'][^>]+src=["']([^"']+)""",
            )?.let { fixUrl(it, getBaseUrl(embedResponse.url)) }
                ?: return

        val rcpResponse = app.get(rcpUrl, referer = getBaseUrl(embedResponse.url))
        val prorcpUrl =
            Regex("""src:\s*['"](/prorcp/[^'"]+)""")
                .find(rcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { fixUrl(it, getBaseUrl(rcpResponse.url)) }
                ?: return

        val prorcpResponse = app.get(prorcpUrl, referer = getBaseUrl(rcpResponse.url))
        val streamUrl = extractStreamUrl(prorcpResponse.text) ?: return
        val segmentReferer = "${getBaseUrl(prorcpResponse.url)}/"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = segmentReferer
                this.headers =
                    mapOf(
                        "Accept" to "*/*",
                        "Referer" to segmentReferer,
                    )
            },
        )
    }

    private fun extractCloudnestraPath(document: org.jsoup.nodes.Document, fallbackRegex: String): String? {
        return document.selectFirst("iframe#player_iframe")?.attr("src")
            ?: Regex(fallbackRegex).find(document.outerHtml())?.groupValues?.getOrNull(1)
    }

    private fun extractStreamUrl(body: String): String? {
        val rawUrl =
            Regex("""file:\s*"([^"]+list\.m3u8)"""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

        val passPath =
            Regex("""pass_path\s*=\s*["'](//[^"']+/rt_ping\.php)["']""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.removePrefix("//")
                ?.substringBefore("/rt_ping.php")
                ?.substringAfter("app2.")

        return if (rawUrl.contains("{v5}") && !passPath.isNullOrBlank()) {
            rawUrl.replace("{v5}", passPath)
        } else if (rawUrl.contains("{v5}")) {
            rawUrl.replace("{v5}", "putgate.org")
        } else {
            rawUrl
        }
    }

    private fun fixUrl(path: String, base: String): String {
        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$base$path"
            else -> "$base/$path"
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

class ByseSayeveum : ByseBase() {
    override var name = "Byse"
    override var mainUrl = "https://bysesayeveum.com"
}

open class ByseBase : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val refererUrl = getBaseUrl(url)
        val playbackRoot = getPlayback(url) ?: return
        val streamUrl = decryptPlayback(playbackRoot.playback) ?: return

        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            mainUrl,
            headers = mapOf("Referer" to refererUrl),
        ).forEach(callback)
    }

    private suspend fun getPlayback(pageUrl: String): PlaybackRoot? {
        val details = getDetails(pageUrl) ?: return null
        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val code = getCodeFromUrl(embedFrameUrl)
        val playbackUrl = "$embedBase/api/videos/$code/embed/playback"
        val headers =
            mapOf(
                "accept" to "*/*",
                "accept-language" to "en-US,en;q=0.5",
                "referer" to embedFrameUrl,
                "x-embed-parent" to pageUrl,
            )
        return app.get(playbackUrl, headers = headers).parsedSafe<PlaybackRoot>()
    }

    private suspend fun getDetails(pageUrl: String): DetailsRoot? {
        val base = getBaseUrl(pageUrl)
        val code = getCodeFromUrl(pageUrl)
        return app.get("$base/api/videos/$code/embed/details").parsedSafe<DetailsRoot>()
    }

    private fun decryptPlayback(playback: Playback): String? {
        val keyBytes = buildAesKey(playback)
        val ivBytes = b64UrlDecode(playback.iv)
        val cipherBytes = b64UrlDecode(playback.payload)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, ivBytes),
        )

        var json = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (json.startsWith("\uFEFF")) json = json.substring(1)
        return tryParseJson<PlaybackDecrypt>(json)?.sources?.firstOrNull()?.url
    }

    private fun buildAesKey(playback: Playback): ByteArray {
        return b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
    }

    private fun b64UrlDecode(value: String): ByteArray {
        val fixed = value.replace('-', '+').replace('_', '/')
        val padding = (4 - fixed.length % 4) % 4
        return base64DecodeArray(fixed + "=".repeat(padding))
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun getCodeFromUrl(url: String): String {
        return URI(url).path.trimEnd('/').substringAfterLast('/')
    }
}

data class DetailsRoot(
    val id: Long,
    val code: String,
    @JsonProperty("embed_frame_url")
    val embedFrameUrl: String,
)

data class PlaybackRoot(
    val playback: Playback,
)

data class Playback(
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts")
    val keyParts: List<String>,
)

data class PlaybackDecrypt(
    val sources: List<PlaybackDecryptSource>? = null,
)

data class PlaybackDecryptSource(
    val url: String,
)
