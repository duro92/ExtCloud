package com.nontontv

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

class NontonTvProvider : MainAPI() {
    override var mainUrl = PLAYLIST_URL
    override var name = "NontonTV Live"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        PLAYLIST_URL to "Live Channels",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val playlist = getPlaylist()
        val groups = playlist.channels
            .groupBy { channel ->
                channel.groupTitle.takeUnless { it.isBlank() } ?: "Other"
            }
            .map { (group, channels) ->
                val items = channels.map { it.toSearchResponse() }
                HomePageList(group, items, isHorizontalImages = true)
            }
        return newHomePageResponse(groups, false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        return getPlaylist().channels
            .asSequence()
            .filter { channel ->
                channel.title.contains(keyword, ignoreCase = true) ||
                    channel.groupTitle.contains(keyword, ignoreCase = true)
            }
            .map { it.toSearchResponse() }
            .distinctBy { it.url }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url) {
            posterUrl = data.posterUrl
            plot = data.groupTitle
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)
        val seen = mutableSetOf<String>()

        loadData.sources.forEachIndexed { index, source ->
            if (!seen.add(source.url)) return@forEachIndexed

            val headers = source.headers.toMutableMap().apply {
                source.userAgent?.takeIf { it.isNotBlank() }?.let { this["User-Agent"] = it }
                source.cookie?.takeIf { it.isNotBlank() }?.let { this["Cookie"] = it }
            }
            val referer = headers["Referer"].orEmpty()
            val linkName = if (loadData.sources.size > 1) {
                "${loadData.title} [${index + 1}]"
            } else {
                loadData.title
            }

            when {
                source.hasDrmKeys() -> {
                    callback(
                        newDrmExtractorLink(
                            source = name,
                            name = linkName,
                            url = source.url,
                            type = INFER_TYPE,
                            uuid = CLEARKEY_UUID
                        ) {
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                            key = source.key!!.trim()
                            kid = source.keyId!!.trim()
                        }
                    )
                }

                !source.licenseUrl.isNullOrBlank() -> {
                    val licenseUrl = source.licenseUrl.trim()
                    callback(
                        newDrmExtractorLink(
                            source = name,
                            name = linkName,
                            url = source.url,
                            type = INFER_TYPE,
                            uuid = CLEARKEY_UUID
                        ) {
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                            this.licenseUrl = licenseUrl
                        }
                    )
                }

                source.url.contains(".mpd", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkName,
                            url = source.url,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = referer
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                }

                source.url.contains(".m3u8", ignoreCase = true) ||
                    source.url.contains(".m3u", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkName,
                            url = source.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                }

                else -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = linkName,
                            url = source.url,
                            type = INFER_TYPE
                        ) {
                            this.referer = referer
                            quality = Qualities.Unknown.value
                            if (headers.isNotEmpty()) this.headers = headers
                        }
                    )
                }
            }
        }

        return loadData.sources.isNotEmpty()
    }

    private suspend fun getPlaylist(): PlaylistData {
        val now = System.currentTimeMillis()
        cachedPlaylist?.takeIf { now - cachedAt < CACHE_TTL_MS }?.let { return it }

        return try {
            val parsed = PlaylistParser.parse(app.get(PLAYLIST_URL).text)
            cachedPlaylist = parsed
            cachedAt = now
            parsed
        } catch (error: Throwable) {
            cachedPlaylist ?: throw ErrorLoadingException(
                error.message ?: "Failed to load playlist"
            )
        }
    }

    private fun ChannelEntry.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            title,
            LoadData(
                title = title,
                posterUrl = posterUrl,
                groupTitle = groupTitle,
                sources = sources
            ).toJson(),
            TvType.Live
        ) {
            this.posterUrl = this@toSearchResponse.posterUrl
            this.lang = groupTitle
        }
    }

    data class LoadData(
        val title: String,
        val posterUrl: String?,
        val groupTitle: String,
        val sources: List<StreamSource>
    )

    data class PlaylistData(
        val channels: List<ChannelEntry>
    )

    data class ChannelEntry(
        val title: String,
        val posterUrl: String?,
        val groupTitle: String,
        val sources: List<StreamSource>
    )

    data class StreamSource(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val userAgent: String? = null,
        val cookie: String? = null,
        val key: String? = null,
        val keyId: String? = null,
        val licenseUrl: String? = null
    ) {
        fun hasDrmKeys(): Boolean {
            return !key.isNullOrBlank() && !keyId.isNullOrBlank() &&
                !key.equals("null", ignoreCase = true) &&
                !keyId.equals("null", ignoreCase = true)
        }
    }

    private object PlaylistParser {
        private const val EXT_INF = "#EXTINF"
        private const val EXT_VLC_OPT = "#EXTVLCOPT"
        private const val EXT_HTTP = "#EXTHTTP:"
        private const val KODI_LICENSE_KEY = "#KODIPROP:inputstream.adaptive.license_key="

        fun parse(content: String): PlaylistData {
            val lines = content.lineSequence().toList()
            val channels = mutableListOf<ChannelEntry>()

            var bufferedTitle: String? = null
            var bufferedAttributes: Map<String, String> = emptyMap()
            var bufferedHeaders: Map<String, String> = emptyMap()
            var bufferedUserAgent: String? = null
            var bufferedCookie: String? = null
            var bufferedKey: String? = null
            var bufferedKeyId: String? = null
            var bufferedLicenseUrl: String? = null
            val bufferedSources = mutableListOf<StreamSource>()

            fun resetBuffers() {
                bufferedTitle = null
                bufferedAttributes = emptyMap()
                bufferedHeaders = emptyMap()
                bufferedUserAgent = null
                bufferedCookie = null
                bufferedKey = null
                bufferedKeyId = null
                bufferedLicenseUrl = null
                bufferedSources.clear()
            }

            fun flushChannel() {
                val title = bufferedTitle?.takeIf { it.isNotBlank() } ?: return
                if (bufferedSources.isEmpty()) {
                    resetBuffers()
                    return
                }

                channels += ChannelEntry(
                    title = title,
                    posterUrl = bufferedAttributes["tvg-logo"].normalizeBlank(),
                    groupTitle = bufferedAttributes["group-title"].normalizeBlank().orEmpty(),
                    sources = bufferedSources.toList()
                )
                resetBuffers()
            }

            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                when {
                    line.startsWith(EXT_INF) -> {
                        flushChannel()
                        bufferedTitle = line.extractTitle()
                        bufferedAttributes = line.extractAttributes()
                    }

                    line.startsWith(EXT_HTTP) -> {
                        val httpHeaders = parseExtHttp(line.removePrefix(EXT_HTTP).trim())
                        if (
                            httpHeaders.headers.isNotEmpty() ||
                            !httpHeaders.cookie.isNullOrBlank() ||
                            !httpHeaders.userAgent.isNullOrBlank()
                        ) {
                            bufferedHeaders = bufferedHeaders + httpHeaders.headers
                            bufferedCookie = httpHeaders.cookie ?: bufferedCookie
                            bufferedUserAgent = httpHeaders.userAgent ?: bufferedUserAgent
                        }
                    }

                    line.startsWith(EXT_VLC_OPT) -> {
                        when {
                            line.startsWith("$EXT_VLC_OPT:http-user-agent=", ignoreCase = true) -> {
                                bufferedUserAgent = line.substringAfter("=").normalizeBlank()
                            }

                            line.startsWith("$EXT_VLC_OPT:http-referrer=", ignoreCase = true) -> {
                                line.substringAfter("=").normalizeBlank()?.let {
                                    bufferedHeaders = bufferedHeaders + ("Referer" to it)
                                }
                            }

                            line.startsWith("$EXT_VLC_OPT:http-origin=", ignoreCase = true) -> {
                                line.substringAfter("=").normalizeBlank()?.let {
                                    bufferedHeaders = bufferedHeaders + ("Origin" to it)
                                }
                            }
                        }
                    }

                    line.startsWith(KODI_LICENSE_KEY, ignoreCase = true) -> {
                        val licenseValue = line.substringAfter("=").trim()
                        when {
                            licenseValue.startsWith("http://", ignoreCase = true) ||
                                licenseValue.startsWith("https://", ignoreCase = true) -> {
                                bufferedLicenseUrl = licenseValue
                            }

                            licenseValue.startsWith("{") -> {
                                parseJsonClearKey(licenseValue)?.let { clearKey ->
                                    bufferedKeyId = clearKey.keyId
                                    bufferedKey = clearKey.key
                                }
                            }

                            else -> {
                                parseHexClearKey(licenseValue)?.let { clearKey ->
                                    bufferedKeyId = clearKey.keyId
                                    bufferedKey = clearKey.key
                                }
                            }
                        }
                    }

                    !line.startsWith("#") && bufferedTitle != null -> {
                        val url = line.extractUrl()?.normalizeBlank() ?: return@forEach
                        val sourceHeaders = bufferedHeaders.toMutableMap()

                        val inlineReferer = line.extractUrlParameter("referer")
                            ?: line.extractUrlParameter("referrer")
                        val inlineOrigin = line.extractUrlParameter("origin")
                        if (!inlineReferer.isNullOrBlank()) sourceHeaders["Referer"] = inlineReferer
                        if (!inlineOrigin.isNullOrBlank()) sourceHeaders["Origin"] = inlineOrigin

                        bufferedSources += StreamSource(
                            url = url,
                            headers = sourceHeaders,
                            userAgent = line.extractUrlParameter("user-agent") ?: bufferedUserAgent,
                            cookie = line.extractUrlParameter("cookie") ?: bufferedCookie,
                            key = line.extractUrlParameter("key") ?: bufferedKey,
                            keyId = line.extractUrlParameter("keyid") ?: bufferedKeyId,
                            licenseUrl = line.extractUrlParameter("licenseUrl") ?: bufferedLicenseUrl
                        )
                    }
                }
            }

            flushChannel()
            return PlaylistData(channels = channels)
        }

        private fun parseExtHttp(rawJson: String): ParsedHttpHeaders {
            return try {
                val values = parseJson<Map<String, String>>(rawJson)
                val headers = buildMap {
                    values.forEach { (key, value) ->
                        when {
                            key.equals("user-agent", ignoreCase = true) -> Unit
                            key.equals("cookie", ignoreCase = true) -> Unit
                            key.equals("referrer", ignoreCase = true) ||
                                key.equals("referer", ignoreCase = true) -> put("Referer", value)
                            key.equals("origin", ignoreCase = true) -> put("Origin", value)
                            else -> put(key, value)
                        }
                    }
                }
                ParsedHttpHeaders(
                    headers = headers,
                    userAgent = values.entries.firstOrNull {
                        it.key.equals("user-agent", ignoreCase = true)
                    }?.value,
                    cookie = values.entries.firstOrNull {
                        it.key.equals("cookie", ignoreCase = true)
                    }?.value
                )
            } catch (_: Throwable) {
                ParsedHttpHeaders()
            }
        }

        private fun parseHexClearKey(rawValue: String): ClearKeyData? {
            val parts = rawValue.split(":", limit = 2)
            if (parts.size != 2) return null

            val kid = parts[0].hexToBase64Url() ?: return null
            val key = parts[1].hexToBase64Url() ?: return null
            return ClearKeyData(keyId = kid, key = key)
        }

        private fun parseJsonClearKey(rawValue: String): ClearKeyData? {
            val key = Regex(""""k"\s*:\s*"([^"]+)"""").find(rawValue)?.groupValues?.getOrNull(1)
            val kid = Regex(""""kid"\s*:\s*"([^"]+)"""").find(rawValue)?.groupValues?.getOrNull(1)
            if (key.isNullOrBlank() || kid.isNullOrBlank()) return null
            return ClearKeyData(keyId = kid, key = key)
        }

        private fun String.extractTitle(): String? {
            val afterPrefix = replace(Regex("""^#EXTINF:.?[0-9]+""", RegexOption.IGNORE_CASE), "").trim()
            var commaIndex = -1
            var insideQuotes = false

            afterPrefix.forEachIndexed { index, char ->
                when (char) {
                    '"' -> insideQuotes = !insideQuotes
                    ',' -> if (!insideQuotes) commaIndex = index
                }
            }

            return if (commaIndex in 0 until afterPrefix.lastIndex) {
                afterPrefix.substring(commaIndex + 1).trim().trim('"')
            } else {
                afterPrefix.substringAfterLast(",").trim().trim('"')
            }
        }

        private fun String.extractAttributes(): Map<String, String> {
            val afterPrefix = replace(Regex("""^#EXTINF:.?[0-9]+""", RegexOption.IGNORE_CASE), "").trim()
            var commaIndex = -1
            var insideQuotes = false

            afterPrefix.forEachIndexed { index, char ->
                when (char) {
                    '"' -> insideQuotes = !insideQuotes
                    ',' -> if (!insideQuotes) commaIndex = index
                }
            }

            val attributesPart = if (commaIndex >= 0) {
                afterPrefix.substring(0, commaIndex)
            } else {
                afterPrefix
            }

            val regex = Regex("""(\w[-\w]*)\s*=\s*(?:"([^"]*)"|([^\s,]+))""")
            return regex.findAll(attributesPart).associate { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2].ifBlank { match.groupValues[3] }.trim()
                key to value
            }
        }

        private fun String.extractUrl(): String? {
            return substringBefore("|").trim().takeIf { it.isNotBlank() }
        }

        private fun String.extractUrlParameter(key: String): String? {
            if (!contains("|")) return null
            val paramSection = substringAfter("|")
            return paramSection.split("&")
                .mapNotNull { part ->
                    val pieces = part.split("=", limit = 2)
                    if (pieces.size == 2 && pieces[0].trim().equals(key, ignoreCase = true)) {
                        pieces[1].trim().trim('"')
                    } else {
                        null
                    }
                }
                .firstOrNull()
        }

        private fun String.hexToBase64Url(): String? {
            val normalized = replace("-", "").replace(" ", "")
            if (normalized.length % 2 != 0 || normalized.isBlank()) return null

            val bytes = normalized.chunked(2).mapNotNull {
                it.toIntOrNull(16)?.toByte()
            }.toByteArray()

            if (bytes.isEmpty()) return null

            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        }
    }

    data class ParsedHttpHeaders(
        val headers: Map<String, String> = emptyMap(),
        val userAgent: String? = null,
        val cookie: String? = null
    )

    data class ClearKeyData(
        val keyId: String,
        val key: String
    )

    companion object {
        private const val PLAYLIST_URL =
            "https://raw.githubusercontent.com/abidinrj/nontontv/main/playlist"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        @Volatile
        private var cachedPlaylist: PlaylistData? = null

        @Volatile
        private var cachedAt: Long = 0L
    }
}

private fun String?.normalizeBlank(): String? = this?.trim()?.takeIf { it.isNotBlank() }
