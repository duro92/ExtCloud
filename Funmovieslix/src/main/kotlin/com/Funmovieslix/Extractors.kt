package com.Funmovieslix

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


class Ryderjet : VidhideExtractor() {
    override var mainUrl = "https://ryderjet.com"
}

class Dhtpre : VidhideExtractor() {
    override var mainUrl = "https://dhtpre.com"
}


class Vidhideplus : VidhideExtractor() {
    override var mainUrl = "https://vidhideplus.com"
}


// Thanks to https://github.com/VVytai/jdownloader_mirror/blob/main/svn_trunk/src/jd/plugins/hoster/LixstreamCom.java
abstract class LixstreamExtractor : ExtractorApi() {
    override val requiresReferer = false

    private val apiBase = "https://api.lixstreamingcaio.com/v2"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sid = url.substringAfterLast("/")
        Log.d("Phisher", "sid=$sid")

        val infoRes = app.post(
            "$apiBase/s/home/resources/$sid",
            data = mapOf(),
            headers = mapOf("Content-Type" to "application/json")
        ).text

        val info = runCatching { JSONObject(infoRes) }.getOrNull() ?: return
        val suid = info.optString("suid") ?: return
        val files = info.optJSONArray("files") ?: return
        if (files.length() == 0) return
        val file = files.optJSONObject(0) ?: return
        val fid = file.optString("id") ?: return
        val assetRes = app.get("$apiBase/s/assets/f?id=$fid&uid=$suid").text
        val asset = runCatching { JSONObject(assetRes) }.getOrNull() ?: return
        val encryptedUrl = asset.optString("url")
        if (encryptedUrl.isNullOrEmpty()) {
            Log.e("Error:", "No encrypted url found")
            return
        }
        val key = "GNgN1lHXIFCQd8hSEZIeqozKInQTFNXj".toByteArray(Charsets.UTF_8)
        val iv = "2Xk4dLo38c9Z2Q2a".toByteArray(Charsets.UTF_8)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv)
        )
        val decrypted = runCatching {
            val decoded = base64DecodeArray(encryptedUrl)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        }.getOrNull() ?: return
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                decrypted,
                if (decrypted.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            )
            {
                this.referer = url
                this.quality = Qualities.P1080.value
            }
        )
    }
}

class VideyV2 : LixstreamExtractor() {
    override var name = "Videy"
    override var mainUrl = "https://videy.tv"
}

class F75s : ExtractorApi() {
    override var name = "F75s"
    override var mainUrl = "https://f75s.com"
    override val requiresReferer = false
    private val pathCodeRegex = Regex("^[A-Za-z0-9]{10,}$")

    private fun decodeBase64Url(value: String): ByteArray {
        val normalized = value + "=".repeat((4 - (value.length % 4)) % 4)
        return Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun extractCode(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val path = clean.substringAfter("://", clean).substringAfter("/", "")
        if (path.isBlank()) return ""

        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""

        val markers = setOf("e", "d", "download", "dwn", "gxtj")
        val markerIndex = segments.indexOfFirst { it.lowercase() in markers }
        if (markerIndex >= 0 && markerIndex + 1 < segments.size) {
            return segments[markerIndex + 1]
        }

        return segments.firstOrNull { pathCodeRegex.matches(it) } ?: segments.last()
    }

    private fun decryptPlayback(playback: JSONObject): JSONObject? {
        val keyParts = playback.optJSONArray("key_parts") ?: return null
        val iv = playback.optString("iv").takeIf { it.isNotBlank() } ?: return null
        val payload = playback.optString("payload").takeIf { it.isNotBlank() } ?: return null
        if (keyParts.length() == 0) return null

        val keyBuffers = (0 until keyParts.length()).mapNotNull { idx ->
            keyParts.optString(idx).takeIf { it.isNotBlank() }?.let { part ->
                runCatching { decodeBase64Url(part) }.getOrNull()
            }
        }
        if (keyBuffers.isEmpty()) return null

        val keySize = keyBuffers.sumOf { it.size }
        val key = ByteArray(keySize)
        var offset = 0
        keyBuffers.forEach { part ->
            System.arraycopy(part, 0, key, offset, part.size)
            offset += part.size
        }

        val ivBytes = runCatching { decodeBase64Url(iv) }.getOrNull() ?: return null
        val payloadBytes = runCatching { decodeBase64Url(payload) }.getOrNull() ?: return null
        if (payloadBytes.size <= 16) return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, ivBytes)
        )
        val decrypted = runCatching { cipher.doFinal(payloadBytes) }.getOrNull() ?: return null
        val decodedText = String(decrypted, StandardCharsets.UTF_8)
        return runCatching { JSONObject(decodedText) }.getOrNull()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = extractCode(url)
        if (code.isBlank()) return

        val embedUrl = "$mainUrl/e/$code"
        val headers = mapOf(
            "X-Embed-Origin" to mainUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
            "X-Embed-Referer" to embedUrl,
            "X-Embed-Parent" to embedUrl,
            "Referer" to embedUrl,
            "Origin" to mainUrl
        )

        val response = runCatching {
            app.get(
                "$mainUrl/api/videos/$code/embed/playback",
                headers = headers
            ).text
        }.getOrNull() ?: return

        val root = runCatching { JSONObject(response) }.getOrNull() ?: return
        val playback = root.optJSONObject("playback") ?: return
        val decrypted = decryptPlayback(playback) ?: return
        val sources = decrypted.optJSONArray("sources") ?: return

        for (idx in 0 until sources.length()) {
            val source = sources.optJSONObject(idx) ?: continue
            val streamUrl = source.optString("url").takeIf { it.isNotBlank() } ?: continue
            val mimeType = source.optString("mime_type").lowercase()
            val sourceLabel = source.optString("label").takeIf { it.isNotBlank() }
            val height = source.optInt("height", 0)
            val quality = if (height > 0) height else Qualities.Unknown.value
            val linkType = if (
                streamUrl.contains(".m3u8", true) || mimeType.contains("mpegurl")
            ) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl,
                    type = linkType
                ) {
                    this.referer = embedUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to embedUrl,
                        "Origin" to mainUrl
                    )
                }
            )
        }
    }
}
