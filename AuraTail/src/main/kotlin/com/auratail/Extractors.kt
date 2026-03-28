package com.auratail

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import org.json.JSONObject

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Minochinos : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://minochinos.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers =
            mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script =
            if (!getPacked(response.text).isNullOrEmpty()) {
                var result = getAndUnpack(response.text)
                if (result.contains("var links")) {
                    result = result.substringAfter("var links")
                }
                result
            } else {
                response.document.selectFirst("script:containsData(sources:)")?.data()
            } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers,
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Dhcplay : StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Serhmeplayer : VidStack() {
    override var name = "Serhmeplayer"
    override var mainUrl = "https://serh.4meplayer.online"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class Dm21 : VidStack() {
    override var name = "Dm21"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

open class Dintezuvio : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dintezuvio.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers =
            mapOf(
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script =
            if (!getPacked(response.text).isNullOrEmpty()) {
                var result = getAndUnpack(response.text)
                if (result.contains("var links")) {
                    result = result.substringAfter("var links")
                }
                result
            } else {
                response.document.selectFirst("script:containsData(sources:)")?.data()
            } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers,
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
        return when {
            url.contains("/d/") -> url.replace("/d/", "/v/")
            url.contains("/download/") -> url.replace("/download/", "/v/")
            url.contains("/file/") -> url.replace("/file/", "/v/")
            else -> url.replace("/f/", "/v/")
        }
    }
}

class Veev : ExtractorApi() {
    override val name = "Veev"
    override val mainUrl = "https://veev.to"
    override val requiresReferer = false

    private val pattern =
        Regex("""(?://|\.)(?:veev|kinoger|poophq|doods)\.(?:to|pw|com)/[ed]/([0-9A-Za-z]+)""")

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaId = pattern.find(url)?.groupValues?.get(1) ?: return
        val pageUrl = "$mainUrl/e/$mediaId"
        val html = app.get(pageUrl, headers = mapOf("User-Agent" to DEFAULT_UA)).text

        val encryptedRegex = Regex("""[.\s'](?:fc|_vvto\[[^]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)""")
        val values = encryptedRegex.findAll(html).map { it.groupValues[1] }.toList()
        if (values.isEmpty()) return

        for (value in values.reversed()) {
            val challenge = veevDecode(value)
            if (challenge == value) continue

            val apiUrl = "$mainUrl/dl?op=player_api&cmd=gi&file_code=$mediaId&r=$mainUrl&ch=$challenge&ie=1"
            val responseText = app.get(apiUrl, headers = mapOf("User-Agent" to DEFAULT_UA)).text
            val json = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                continue
            }

            val file = json.optJSONObject("file") ?: continue
            if (file.optString("file_status") != "OK") continue

            val decoded = decodeUrl(veevDecode(file.getJSONArray("dv").getJSONObject(0).getString("s")), buildArray(challenge)[0])
            callback.invoke(
                newExtractorLink(name, name, decoded, INFER_TYPE) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                },
            )
            return
        }
    }

    private fun veevDecode(encrypted: String): String {
        val result = StringBuilder()
        val dictionary = HashMap<Int, String>()
        var nextCode = 256
        var current = encrypted[0].toString()
        result.append(current)

        for (char in encrypted.drop(1)) {
            val code = char.code
            val next = if (code < 256) char.toString() else dictionary[code] ?: (current + current[0])
            result.append(next)
            dictionary[nextCode++] = current + next[0]
            current = next
        }
        return result.toString()
    }

    private fun jsInt(char: Char): Int = char.digitToIntOrNull() ?: 0

    private fun buildArray(encoded: String): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        val iterator = encoded.iterator()

        fun nextIntOrZero(): Int = if (iterator.hasNext()) jsInt(iterator.nextChar()) else 0

        var count = nextIntOrZero()
        while (count != 0) {
            val row = mutableListOf<Int>()
            repeat(count) {
                row.add(nextIntOrZero())
            }
            result.add(row.reversed())
            count = nextIntOrZero()
        }
        return result
    }

    private fun decodeUrl(encoded: String, rules: List<Int>): String {
        var text = encoded
        for (rule in rules) {
            if (rule == 1) text = text.reversed()
            val bytes = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            text = bytes.toString(Charsets.UTF_8).replace("dXRmOA==", "")
        }
        return text
    }
}
