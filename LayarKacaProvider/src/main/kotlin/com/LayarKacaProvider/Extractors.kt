package com.layarKacaProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.extractors.EmturbovidExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


class Co4nxtrl : Filesim() {
    override val mainUrl = "https://co4nxtrl.com"
    override val name = "Co4nxtrl"
    override val requiresReferer = true
}


open class Hownetwork : ExtractorApi() {
    override val name = "Hownetwork"
    override val mainUrl = "https://stream.hownetwork.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfter("id=")
        val response = app.post(
            "$mainUrl/api2.php?id=$id",
            data = mapOf(
                "r" to "",
                "d" to mainUrl,
            ),
            referer = url,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text
        val json = JSONObject(response)
        val file = json.optString("file")
        callback.invoke(newExtractorLink(
            this.name,
            this.name,
            file,
            type = INFER_TYPE,
            {
                this.referer = file
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Sec-GPC" to "1",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Priority" to "u=0",
                    "Pragma" to "no-cache",
                    "Cache-Control" to "no-cache",
                    "TE" to "trailers"
                )
            }
        ))
    }
}

class Cloudhownetwork : Hownetwork() {
    override var mainUrl = "https://cloud.hownetwork.xyz"
}

class Furher : Filesim() {
    override val name = "Furher"
    override var mainUrl = "https://furher.in"
}

class Furher2 : Filesim() {
    override val name = "Furher 2"
    override var mainUrl = "723qrh1p.fun"
}


class Emturbovid : EmturbovidExtractor() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"
}

class F16px : Filesim() {
    override var mainUrl = "https://f16px.com"
    override var name = "F16px"
    override val requiresReferer = true
}

open class ByseSX : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return base64DecodeArray(fixed + "=".repeat(pad))
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private fun getCodeFromUrl(url: String): String {
        val path = URI(url).path ?: ""
        return path.trimEnd('/').substringAfterLast('/')
    }

    private suspend fun getDetails(mainUrl: String): DetailsRoot? {
        val base = getBaseUrl(mainUrl)
        val code = getCodeFromUrl(mainUrl)
        val url = "$base/api/videos/$code/embed/details"
        return app.get(url).parsedSafe<DetailsRoot>()
    }

    private suspend fun getPlayback(mainUrl: String): PlaybackRoot? {
        val details = getDetails(mainUrl) ?: return null
        val embedFrameUrl = details.embedFrameUrl
        val embedBase = getBaseUrl(embedFrameUrl)
        val code = getCodeFromUrl(embedFrameUrl)
        val playbackUrl = "$embedBase/api/videos/$code/embed/playback"
        val headers = mapOf(
            "accept" to "*/*",
            "accept-language" to "en-US,en;q=0.5",
            "priority" to "u=1, i",
            "referer" to embedFrameUrl,
            "x-embed-parent" to mainUrl,
        )
        return app.get(playbackUrl, headers = headers).parsedSafe<PlaybackRoot>()
    }

    private fun buildAesKey(playback: Playback): ByteArray {
        val p1 = b64UrlDecode(playback.keyParts[0])
        val p2 = b64UrlDecode(playback.keyParts[1])
        return p1 + p2
    }

    private fun decryptPlayback(playback: Playback): String? {
        val keyBytes = buildAesKey(playback)
        val ivBytes = b64UrlDecode(playback.iv)
        val cipherBytes = b64UrlDecode(playback.payload)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, ivBytes)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plainBytes = cipher.doFinal(cipherBytes)
        var jsonStr = String(plainBytes, StandardCharsets.UTF_8)

        if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1)

        val root = try {
            tryParseJson<PlaybackDecrypt>((jsonStr))
        } catch (_: Exception) {
            return null
        }

        return root?.sources?.firstOrNull()?.url
    }


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val refererUrl = getBaseUrl(url)
        val playbackRoot = getPlayback(url) ?: return
        val streamUrl  = decryptPlayback(playbackRoot.playback) ?: return


        val headers = mapOf("Referer" to refererUrl)
        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            mainUrl,
            headers = headers
        ).forEach(callback)
    }
}

data class DetailsRoot(
    val id: Long,
    val code: String,
    val title: String,
    @JsonProperty("poster_url")
    val posterUrl: String,
    val description: String,
    @JsonProperty("created_at")
    val createdAt: String,
    @JsonProperty("owner_private")
    val ownerPrivate: Boolean,
    @JsonProperty("embed_frame_url")
    val embedFrameUrl: String,
)

data class PlaybackRoot(
    val playback: Playback,
)

data class Playback(
    val algorithm: String,
    val iv: String,
    val payload: String,
    @JsonProperty("key_parts")
    val keyParts: List<String>,
    @JsonProperty("expires_at")
    val expiresAt: String,
    @JsonProperty("decrypt_keys")
    val decryptKeys: DecryptKeys,
    val iv2: String,
    val payload2: String,
)

data class DecryptKeys(
    @JsonProperty("edge_1")
    val edge1: String,
    @JsonProperty("edge_2")
    val edge2: String,
    @JsonProperty("legacy_fallback")
    val legacyFallback: String,
)

data class PlaybackDecrypt(
    val sources: List<PlaybackDecryptSource>,
)

data class PlaybackDecryptSource(
    val quality: String,
    val label: String,
    @JsonProperty("mime_type")
    val mimeType: String,
    val url: String,
    @JsonProperty("bitrate_kbps")
    val bitrateKbps: Long,
    val height: Any?,
)