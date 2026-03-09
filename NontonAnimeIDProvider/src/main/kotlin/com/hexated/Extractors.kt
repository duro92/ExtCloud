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
import java.net.URLDecoder
import java.util.Base64

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
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to USER_AGENT)
        )
        val html = response.text
        val document = response.document
        val referer = preferredKotakReferer(url)
        val refererOrigin = originOf(referer)

        val sources = mutableListOf<ExtractorLink>()
        sources.addAll(buildLinksFromUrls(extractFromVidParam(url), referer, refererOrigin))
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

class Gdriveplayerto : ExtractorApi() {
    override val name = "Gdriveplayer"
    override val mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val html = response.text
        val document = response.document

        val ids = Regex("""\b(?:var|let|const)\s+ids\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""list_([a-f0-9]{16,64})""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
        val setCookie = response.headers["Set-Cookie"] ?: response.headers["set-cookie"]
        val cookieHeader = ids?.let { "newaccess=$it" }
            ?: setCookie
                ?.substringBefore(";")
                ?.takeIf { it.startsWith("newaccess=") }

        val token = document.selectFirst("#token")?.text()?.trim()
            ?.ifBlank { null }
            ?: Regex("""id=["']token["'][^>]*>\s*([A-Za-z0-9+/=]+)\s*<""")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        val playlistUrl = when {
            !token.isNullOrBlank() -> "$mainUrl/hlsplaylist.php?s=$token"
            else -> findFirstUrl(html, "hlsplaylist.php")?.let { normalizeUrl(it, mainUrl) }
                ?: findFirstUrl(html, "hlsnew2.php")?.let { normalizeUrl(it, mainUrl) }
        } ?: return

        val commonHeaders = mapOf(
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        ) + (cookieHeader?.let { mapOf("Cookie" to it) } ?: emptyMap())

        val playlistText = app.get(
            playlistUrl,
            referer = url,
            headers = commonHeaders
        ).text

        val variants = parseM3u8(playlistText, playlistUrl, name)
        val fallbackVariants = if (variants.isEmpty()) {
            extractHlsNew2Variants(playlistText, playlistUrl, name)
        } else {
            emptyList()
        }

        val finalVariants = if (variants.isNotEmpty()) variants else fallbackVariants

        if (finalVariants.isEmpty()) {
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    playlistUrl,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.headers = commonHeaders
                }
            )
            return
        }

        finalVariants.forEach { variant ->
            callback.invoke(
                newExtractorLink(
                    name,
                    variant.label,
                    variant.url,
                    INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = variant.quality
                    this.headers = commonHeaders
                }
            )
        }
    }
}

class KotakAnimeidCom : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.com"
}

class KotakAnimeidLink : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://kotakanimeid.link"
}

class KotakAnimeidS1 : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://s1.kotakanimeid.link"
}

class KotakAnimeidS2 : KotakAnimeidBase() {
    override val name = "KotakAnimeid"
    override val mainUrl = "https://s2.kotakanimeid.link"
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
            Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(unpacked)
                .map { it.groupValues[1] }
                .forEach { direct ->
                    sources.addAll(buildLinksFromUrls(listOf(direct), referer, origin))
                }
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
            Regex("""(?:file|src)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(data)
                .map { it.groupValues[1] }
                .forEach { direct ->
                    sources.addAll(buildLinksFromUrls(listOf(direct), referer, origin))
                }
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

    // Some KotakAnimeID embeds use signed Google "videoplayback" URLs without file extension.
    val patternGoogleVideo = Regex(
        """https?://[^\s"'\\]+/videoplayback\?[^\s"'\\]+""",
        RegexOption.IGNORE_CASE
    )
    patternGoogleVideo.findAll(cleaned).forEach { match ->
        urls.add(match.value)
    }

    // Generic signed stream endpoints often carry mime/video hints in query.
    val patternQueryStream = Regex(
        """https?://[^\s"'\\]+\?(?:[^\s"'\\]*&)?(?:mime=video|source=|itag=|dur=)[^\s"'\\]*""",
        RegexOption.IGNORE_CASE
    )
    patternQueryStream.findAll(cleaned).forEach { match ->
        urls.add(match.value)
    }

    return urls.toList()
}

private fun findFirstUrl(html: String, needle: String): String? {
    return Regex("""https?://[^\s"'\\>]+$needle[^\s"'\\>]*""", RegexOption.IGNORE_CASE)
        .find(html)
        ?.value
        ?: Regex("""/[^\s"'\\>]*$needle[^\s"'\\>]*""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.value
}

private fun normalizeUrl(url: String, base: String): String {
    return when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> base.trimEnd('/') + "/" + url.trimStart('/')
    }
}

private data class M3u8Variant(
    val url: String,
    val quality: Int,
    val label: String
)

private fun parseM3u8(playlist: String, baseUrl: String, sourceName: String): List<M3u8Variant> {
    val normalized = playlist.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return emptyList()

    val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
    if (!isMaster) return emptyList()

    val variants = mutableListOf<M3u8Variant>()
    var pendingQuality: Int? = null

    fun buildUrl(line: String): String {
        return if (line.startsWith("http")) {
            line
        } else if (line.startsWith("//")) {
            "https:$line"
        } else {
            val base = baseUrl.substringBeforeLast("/") + "/"
            base + line.trimStart('/')
        }
    }

    for (line in lines) {
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            val res = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val bw = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            pendingQuality = res ?: bw?.let { (it / 1000).coerceAtLeast(144) }
            continue
        }
        if (line.startsWith("#")) continue
        val url = buildUrl(line)
        val quality = pendingQuality
            ?: extractQualityFromUrl(url)
            ?: Qualities.Unknown.value
        val label = if (quality == Qualities.Unknown.value) sourceName else "$sourceName ${quality}p"
        variants.add(M3u8Variant(url, quality, label))
        pendingQuality = null
    }

    if (variants.isNotEmpty()) return variants

    val urls = collectStreamUrls(playlist).map { normalizeUrl(it, baseUrl) }
    return urls.map { url ->
        val quality = extractQualityFromUrl(url) ?: Qualities.Unknown.value
        val label = if (quality == Qualities.Unknown.value) sourceName else "$sourceName ${quality}p"
        M3u8Variant(url, quality, label)
    }
}

private fun extractHlsNew2Variants(
    playlist: String,
    baseUrl: String,
    sourceName: String
): List<M3u8Variant> {
    val matches = Regex("""hlsnew2\.php\?[^\\s"']+""", RegexOption.IGNORE_CASE)
        .findAll(playlist)
        .map { it.value }
        .toList()
    if (matches.isEmpty()) return emptyList()

    val base = originOf(baseUrl)
    val urls = matches
        .map { normalizeUrl(it, base) }
        .distinct()

    return urls.map { url ->
        val quality = extractQualityFromUrl(url) ?: Qualities.Unknown.value
        val label = if (quality == Qualities.Unknown.value) sourceName else "$sourceName ${quality}p"
        M3u8Variant(url, quality, label)
    }
}

private fun extractQualityFromUrl(url: String): Int? {
    val byType = Regex("[?&](?:type|res)=(\\d{3,4})", RegexOption.IGNORE_CASE)
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (byType != null) return byType
    val byPath = Regex("/(\\d{3,4})p/", RegexOption.IGNORE_CASE)
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return byPath
}

private fun extractFromVidParam(url: String): List<String> {
    val vid = URL(url).query?.substringAfter("vid=", "")?.substringBefore("&").orEmpty()
    if (vid.isBlank()) return emptyList()
    val decoded = runCatching {
        URLDecoder.decode(vid, "UTF-8")
    }.getOrDefault(vid)
    val candidates = mutableListOf<String>()

    fun tryDecodeBase64(input: String): String? {
        val cleaned = input.replace('-', '+').replace('_', '/')
        val pad = (4 - (cleaned.length % 4)) % 4
        val padded = cleaned + "=".repeat(pad)
        return runCatching {
            String(Base64.getDecoder().decode(padded))
        }.getOrNull()
    }

    candidates.add(decoded)
    tryDecodeBase64(decoded)?.let { candidates.add(it) }
    tryDecodeBase64(decoded.replace("%3D", "="))?.let { candidates.add(it) }

    val urls = LinkedHashSet<String>()
    candidates.forEach { text ->
        collectStreamUrls(text).forEach { urls.add(it) }
        // If only path is present, try to reconstruct full URL
        if (text.contains("season/") && text.contains(".m3u8")) {
            val path = text.substringAfter("season/").substringBefore(".m3u8")
            val rebuilt = "https://cdn2.kotakanimeid.link/season/$path.m3u8"
            urls.add(rebuilt)
        }
    }
    return urls.toList()
}

private suspend fun buildLinksFromUrls(
    urls: List<String>,
    referer: String,
    origin: String
): List<ExtractorLink> {
    if (urls.isEmpty()) return emptyList()
    val hasHls = urls.any { it.contains(".m3u8", ignoreCase = true) }
    val filtered = if (hasHls) {
        urls.filter { it.contains(".m3u8", ignoreCase = true) }
    } else {
        urls
    }
    return filtered.map { link ->
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
    return "${parsed.protocol}://${parsed.host}/"
}

private data class ResponseSource(
    @JsonProperty("file") val file: String,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("label") val label: String? = null
)
