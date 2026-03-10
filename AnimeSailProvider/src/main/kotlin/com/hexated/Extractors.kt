package com.hexated

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class MixDropBz : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://m1xdrop.bz"
    override val requiresReferer = false

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/e/$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/f/", "/e/")
        val response = app.get(
            embedUrl,
            referer = referer ?: "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT)
        )

        val scriptChunks = linkedSetOf<String>()
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isBlank()) return@forEach
            if (data.contains("MDCore", ignoreCase = true) ||
                data.contains("wurl", ignoreCase = true) ||
                data.contains("furl", ignoreCase = true) ||
                data.contains("eval(function(p,a,c,k,e,d)")
            ) {
                scriptChunks.add(data)
            }
            if (data.contains("eval(function(p,a,c,k,e,d)")) {
                runCatching { getAndUnpack(data) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { scriptChunks.add(it) }
            }
        }

        val streamRegexes = listOf(
            Regex("""(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:MDCore\.)?furl\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )

        fun normalizeCandidate(raw: String): String? {
            val normalized = raw
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
                .trim()
            val absolute = when {
                normalized.startsWith("http://", true) || normalized.startsWith("https://", true) -> normalized
                normalized.startsWith("//") -> "https:$normalized"
                else -> return null
            }
            return absolute.takeIf {
                it.contains(".mp4", ignoreCase = true) || it.contains(".m3u8", ignoreCase = true)
            }
        }

        suspend fun isPlayable(candidate: String): Boolean {
            val res = runCatching {
                app.get(
                    candidate,
                    referer = embedUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to mainUrl,
                        "Range" to "bytes=0-4095"
                    )
                )
            }.getOrNull() ?: return false

            val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty().lowercase()
            val bytes = runCatching { res.body.bytes().take(4096).toByteArray() }.getOrNull() ?: return false
            val isMp4 = bytes.size >= 8 &&
                bytes[4] == 0x66.toByte() &&
                bytes[5] == 0x74.toByte() &&
                bytes[6] == 0x79.toByte() &&
                bytes[7] == 0x70.toByte()
            val headText = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
            return ct.contains("video/mp4") || isMp4 || headText.contains("#EXTM3U")
        }

        val candidates = linkedSetOf<String>()
        scriptChunks.forEach { blob ->
            streamRegexes.forEach { regex ->
                regex.findAll(blob).forEach { m ->
                    m.groupValues.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && it != " " }
                        ?.let(::normalizeCandidate)
                        ?.let(candidates::add)
                }
            }
        }
        if (candidates.isEmpty()) return
        val streamUrl = candidates.firstOrNull { isPlayable(it) } ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = INFER_TYPE
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to mainUrl
                )
            }
        )
    }
}

class Mp4UploadFix : ExtractorApi() {
    override var name = "Mp4Upload"
    override var mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    private val idMatch = Regex("""mp4upload\.com/(?:embed-|)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val srcRegex = Regex("""player\.src\(\s*\{[\w\W]*?src:\s*"(.*?)"""", RegexOption.IGNORE_CASE)
    private val srcRegex2 = Regex("""player\.src\(\s*"(.*?)"""", RegexOption.IGNORE_CASE)

    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
            bytes[4] == 0x66.toByte() &&
            bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() &&
            bytes[7] == 0x70.toByte()
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/embed-$id.html"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = idMatch.find(url)?.groupValues?.getOrNull(1)
        val realUrl = id?.let { "$mainUrl/embed-$it.html" } ?: url
        val embedReferer = realUrl
        val watchReferer = "$mainUrl/"

        fun normalizeRedirect(location: String?): String? {
            if (location.isNullOrBlank()) return null
            return when {
                location.startsWith("http://", true) || location.startsWith("https://", true) -> location
                location.startsWith("//") -> "https:$location"
                location.startsWith("/") -> "$mainUrl$location"
                else -> null
            }
        }

        suspend fun isPlayable(candidateUrl: String, refererHeader: String): Boolean {
            val res = runCatching {
                app.get(
                    candidateUrl,
                    referer = refererHeader,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to refererHeader,
                        "Origin" to mainUrl,
                        "Range" to "bytes=0-4095"
                    )
                )
            }.getOrNull() ?: return false

            val ct = (res.headers["Content-Type"] ?: res.headers["content-type"]).orEmpty().lowercase()
            val bytes = runCatching { res.body.bytes().take(4096).toByteArray() }.getOrNull() ?: return false
            return ct.contains("video") || ct.contains("octet-stream") && looksLikeMp4(bytes)
        }

        // Prefer download2 endpoint on 443 (more compatible than :183 direct stream URLs).
        if (!id.isNullOrBlank()) {
            val download2Url = "$mainUrl/dl?op=download2&id=$id"
            val redirect = runCatching {
                app.get(
                    download2Url,
                    referer = watchReferer,
                    allowRedirects = false,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to watchReferer,
                        "Origin" to mainUrl
                    )
                )
            }.getOrNull()

            val finalUrl = normalizeRedirect(
                redirect?.headers?.get("Location") ?: redirect?.headers?.get("location")
            )

            if (!finalUrl.isNullOrBlank() && isPlayable(finalUrl, watchReferer)) {
                return listOf(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = watchReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to watchReferer,
                            "Origin" to mainUrl
                        )
                    }
                )
            }
        }

        val response = app.get(
            realUrl,
            referer = referer ?: "$mainUrl/",
            headers = mapOf("User-Agent" to USER_AGENT)
        )

        val scriptBlobs = linkedSetOf<String>()
        scriptBlobs.add(response.text)
        runCatching { getAndUnpack(response.text) }.getOrNull()?.let { scriptBlobs.add(it) }
        response.document.select("script").forEach { script ->
            val data = script.data().trim()
            if (data.isNotBlank()) {
                scriptBlobs.add(data)
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(data) }.getOrNull()?.let { scriptBlobs.add(it) }
                }
            }
        }

        val streamUrl = scriptBlobs.firstNotNullOfOrNull { blob ->
            srcRegex.find(blob)?.groupValues?.getOrNull(1)?.trim()
                ?: srcRegex2.find(blob)?.groupValues?.getOrNull(1)?.trim()
        }?.takeIf { it.isNotBlank() } ?: return null

        val quality = scriptBlobs.firstNotNullOfOrNull { blob ->
            Regex("""height\s*[:=]\s*["']?(\d{3,4})""", RegexOption.IGNORE_CASE)
                .find(blob)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        } ?: Qualities.Unknown.value

        val fixedStream = when {
            streamUrl.startsWith("http://", true) || streamUrl.startsWith("https://", true) -> streamUrl
            streamUrl.startsWith("//") -> "https:$streamUrl"
            else -> return null
        }

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = fixedStream,
                type = INFER_TYPE
            ) {
                this.referer = watchReferer
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to mainUrl
                )
            }
        )
    }
}
