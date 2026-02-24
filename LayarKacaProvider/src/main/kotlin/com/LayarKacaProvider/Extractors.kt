package com.layarKacaProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val ref = referer ?: "$mainUrl/"

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*"
        )

        val page = app.get(url, referer = ref)

        val playerScript = page.document
            .selectXpath("//script[contains(text(),'var urlPlay')]")
            .html()

        if (playerScript.isBlank()) return null

        var masterUrl = playerScript
            .substringAfter("var urlPlay = '")
            .substringBefore("'")
            .trim()

    
        if (masterUrl.startsWith("//")) masterUrl = "https:$masterUrl"
        if (masterUrl.startsWith("/")) masterUrl = mainUrl + masterUrl

        val masterText = app.get(masterUrl, headers = headers).text
        val lines = masterText.lines()

        val out = mutableListOf<ExtractorLink>()

        for (i in 0 until lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue

            val height = Regex("RESOLUTION=\\d+x(\\d+)")
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (next.isBlank() || next.startsWith("#")) continue

            var variantUrl = next
            if (variantUrl.startsWith("//")) variantUrl = "https:$variantUrl"
            else if (variantUrl.startsWith("/")) variantUrl = mainUrl + variantUrl

            val q = height ?: Qualities.Unknown.value

            out += newExtractorLink(
                source = name,
                name = name,
                url = variantUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = headers
                this.quality = q
            }
        }

        if (out.isEmpty()) {
            out += newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        }

        return out
    }
}


