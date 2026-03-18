package com.filmkita

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import java.net.URLDecoder

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
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

class Minochinos : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://minochinos.com"
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

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
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

class Vidshare : VidStack() {
    override var name = "Vidshare"
    override var mainUrl = "https://vidshare.rpmvid.com"
    override var requiresReferer = true
}

class Winvids : VidStack() {
    override var name = "Winvids"
    override var mainUrl = "https://winvids.strp2p.com"
    override var requiresReferer = true
}

class LayarwibuHls : ExtractorApi() {
    override val name = "Layarwibu HLS"
    override val mainUrl = "https://hls-bekop.layarwibu.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers =
            mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )

        val streamUrl =
            when {
                url.contains("/player2/") -> {
                    val encoded = url.substringAfterLast("/").substringBefore("?")
                    runCatching {
                            base64Decode(URLDecoder.decode(encoded, "UTF-8"))
                                .trim()
                                .takeIf { it.startsWith("http") }
                        }
                        .getOrNull()
                }
                url.contains(".m3u8") -> url
                else -> null
            } ?: return

        generateM3u8(
            name,
            streamUrl,
            referer = "$mainUrl/",
            headers = headers,
        ).forEach(callback)
    }
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

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
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
