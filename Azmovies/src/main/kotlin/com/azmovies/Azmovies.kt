package com.azmovies

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class Azmovies : MainAPI() {
    override var mainUrl = "https://azmovies.to"
    override var name = "Azmovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage =
        mainPageOf(
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=newest&page=%d" to "Newest",
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Featured",
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=rating&page=%d" to "Top Rating",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data.format(page)).document
        val home = document.select("#movies-container a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url =
            "$mainUrl/search?q=${query.urlEncode()}&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured"
        return request(url).document.select("#movies-container a.poster").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.movie-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.movie-poster img")?.attr("src").toAbsoluteUrl()
        val backgroundPoster = document.selectFirst("div.movie-hero")?.attr("style")?.extractBackgroundUrl()
        val year = document.selectFirst("div.movie-meta span:not(.movie-rating)")?.text()?.trim()?.toIntOrNull()
        val duration = document.select("div.movie-meta span.has-icon").firstOrNull()?.text()?.toMinutes()
        val ratingText = document.selectFirst("span.movie-rating")?.text()?.trim()
        val plot = document.selectFirst("div.movie-overview p")?.text()?.trim()
        val tags = document.select("div.movie-genres a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors =
            document.select("div.movie-cast .cast-card").mapNotNull { card ->
                val actorName = card.selectFirst("strong")?.text()?.trim() ?: return@mapNotNull null
                val role = card.selectFirst("span")?.text()?.trim()
                val image = card.selectFirst("img")?.attr("src").toAbsoluteUrl()
                ActorData(Actor(actorName, image), roleString = role)
            }
        val recommendations =
            document
                .select("div.simple-carousel a.poster")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            backgroundPosterUrl = backgroundPoster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = duration
            addScore(ratingText)
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = request(data)
        val document = response.document
        val seenSubtitles = linkedSetOf<String>()
        val servers = extractServerButtons(response.text, document)

        servers.forEach { button ->
            val rawUrl = button.url.replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(subtitle)
                }
            }

            val label = buildString {
                append(button.server.ifBlank { "Server" })
                val quality = button.quality.trim()
                if (quality.isNotBlank()) {
                    append(" ")
                    append(quality)
                }
            }.trim()

            runCatching {
                if (rawUrl.contains("vidsrc.xyz", true)) {
                    val handled = loadVidsrc(rawUrl, button.quality, callback)
                    if (!handled) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = label.ifBlank { "VidSrc" },
                                url = rawUrl,
                                type = com.lagradost.cloudstream3.utils.INFER_TYPE,
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = getQualityFromName(button.quality)
                            },
                        )
                    }
                } else {
                    loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }.onFailure {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank { name },
                        url = rawUrl,
                        type = com.lagradost.cloudstream3.utils.INFER_TYPE,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(button.quality)
                    },
                )
            }
        }

        return true
    }

    private suspend fun loadVidsrc(
        url: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = normalizeVidsrcUrl(url)
        val embedResponse =
            app.get(
                embedUrl,
                referer = "$mainUrl/",
                headers =
                    mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "User-Agent" to USER_AGENT,
                    ),
            )
        val rcpUrl =
            (
                embedResponse.document.selectFirst("iframe#player_iframe")?.attr("src")
                    ?: Regex("""<iframe[^>]+id=["']player_iframe["'][^>]+src=["']([^"']+)""")
                        .find(embedResponse.text)
                        ?.groupValues
                        ?.getOrNull(1)
                    ?: Regex("""data-hash=["']([^"']+)["']""")
                        .find(embedResponse.text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { "//cloudnestra.com/rcp/$it" }
            )?.toAbsoluteUrl(getBaseUrl(embedResponse.url))
                ?: return false

        val rcpResponse = app.get(rcpUrl, referer = "${getBaseUrl(embedResponse.url)}/")
        val prorcpUrl =
            Regex("""src:\s*['"](/prorcp/[^'"]+)""")
                .find(rcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toAbsoluteUrl(getBaseUrl(rcpResponse.url))
                ?: return false

        val prorcpResponse = app.get(prorcpUrl, referer = "${getBaseUrl(rcpResponse.url)}/")
        val rawStreamUrl =
            Regex("""file:\s*"([^"]+list\.m3u8)"""")
                .find(prorcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?: return false
        val passHost =
            Regex("""pass_path\s*=\s*["'](//[^"']+/rt_ping\.php)["']""")
                .find(prorcpResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.removePrefix("//")
                ?.substringBefore("/rt_ping.php")
                ?.substringAfter("app2.")
                ?: "putgate.org"
        val streamUrl =
            if (rawStreamUrl.contains("{v5}")) {
                rawStreamUrl.replace("{v5}", passHost)
            } else {
                rawStreamUrl
            }
        val segmentReferer = "${getBaseUrl(prorcpResponse.url)}/"
        val playlistHeaders =
            mapOf(
                "Accept" to "*/*",
                "Referer" to segmentReferer,
                "Origin" to getBaseUrl(prorcpResponse.url),
                "User-Agent" to USER_AGENT,
            )
        val playlistItems = decodeVidsrcPlaylist(streamUrl, playlistHeaders) ?: return false
        val playlistLink =
            buildPlaylistExtractorLink(
                linkName = "VidSrc ${qualityLabel.trim()}".trim(),
                quality = getQualityFromName(qualityLabel),
                referer = segmentReferer,
                headers = playlistHeaders,
                items = playlistItems,
            )
                ?: return false

        callback(
            playlistLink,
        )
        return true
    }

    private suspend fun decodeVidsrcPlaylist(
        streamUrl: String,
        headers: Map<String, String>,
    ): List<VidsrcPlaylistItem>? {
        val playlistBody = app.get(streamUrl, headers = headers).text
        val decodedBody = decodeAsciiPlaylist(playlistBody)
        if (!decodedBody.contains("#EXTM3U")) return null

        val items = mutableListOf<VidsrcPlaylistItem>()
        var currentDurationUs = 0L

        decodedBody.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:", true) -> {
                    val seconds = line.substringAfter("#EXTINF:").substringBefore(",").toDoubleOrNull()
                    currentDurationUs = ((seconds ?: 0.0) * 1_000_000L).toLong()
                }
                line.isBlank() || line.startsWith("#") -> Unit
                else -> {
                    items += VidsrcPlaylistItem(line.toAbsoluteUrl(streamUrl) ?: line, currentDurationUs)
                    currentDurationUs = 0L
                }
            }
        }

        return items.takeIf { it.isNotEmpty() }
    }

    private fun decodeAsciiPlaylist(body: String): String {
        if (body.contains("#EXTM3U")) return body

        val values =
            body
                .lineSequence()
                .map { it.trim() }
                .filter { it.matches(Regex("""\d+""")) }
                .mapNotNull { it.toIntOrNull() }
                .toList()

        if (values.isEmpty()) return body
        return buildString(values.size) {
            values.forEach { append(it.toChar()) }
        }
    }

    private fun normalizeVidsrcUrl(url: String): String {
        if (!url.contains("vidsrc", true) && !url.contains("vsembed", true)) return url
        if (url.contains("autoplay=", true)) return url
        val joiner = if (url.contains("?")) "&" else "?"
        return "$url${joiner}autoplay=1"
    }

    private fun buildPlaylistExtractorLink(
        linkName: String,
        quality: Int,
        referer: String,
        headers: Map<String, String>,
        items: List<VidsrcPlaylistItem>,
    ): ExtractorLink? {
        return runCatching {
            val playListItemClass = Class.forName("com.lagradost.cloudstream3.utils.PlayListItem")
            val playListItemCtor = playListItemClass.getConstructor(String::class.java, java.lang.Long.TYPE)
            val playListObjects =
                items.map { item ->
                    playListItemCtor.newInstance(item.url, item.durationUs)
                }

            val extractorClass = Class.forName("com.lagradost.cloudstream3.utils.ExtractorLinkPlayList")
            val ctor =
                extractorClass.getConstructor(
                    String::class.java,
                    String::class.java,
                    List::class.java,
                    String::class.java,
                    Integer.TYPE,
                    Map::class.java,
                    String::class.java,
                    ExtractorLinkType::class.java,
                    List::class.java,
                )

            ctor.newInstance(
                name,
                linkName,
                playListObjects,
                referer,
                quality,
                headers,
                "",
                ExtractorLinkType.M3U8,
                emptyList<Any>(),
            ) as? ExtractorLink
        }.getOrNull()
    }

    private suspend fun request(url: String): NiceResponse {
        val response =
            app.get(
                url,
                headers = headers,
                timeout = 30L,
            )
        if (!response.isVerificationPage()) return response

        val token = response.text.substringAfter("var verifyToken = \"", "").substringBefore("\"")
        if (token.isBlank()) return response
        val cookies = response.cookies

        app.post(
            "$mainUrl/verified",
            headers =
                headers +
                    mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
            cookies = cookies,
            requestBody =
                mapOf("token" to token)
                    .toJson()
                    .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
        )

        return app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30L,
        )
    }

    private fun NiceResponse.isVerificationPage(): Boolean {
        return text.contains("Verifying your browser", true) && text.contains("var verifyToken")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        val title = selectFirst("span.poster__title")?.text()?.trim() ?: return null
        val poster =
            (
                selectFirst("img.poster__img")?.attr("data-src")
                    ?: selectFirst("img.poster__img")?.attr("src")
            ).toAbsoluteUrl()
        val year = selectFirst("span.badge")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun extractSubtitle(url: String): SubtitleFile? {
        val subtitleUrl = url.substringAfter("c1_file=", "").substringBefore("&").urlDecode()
        if (subtitleUrl.isBlank() || !subtitleUrl.contains(".vtt", true)) return null
        val label = url.substringAfter("c1_label=", "English").substringBefore("&").urlDecode()
        return SubtitleFile(label.ifBlank { "English" }, subtitleUrl)
    }

    private fun extractServerButtons(html: String, document: org.jsoup.nodes.Document): List<ServerButton> {
        val regexButtons =
            Regex(
                """<button[^>]*class=["'][^"']*server-btn[^"']*["'][^>]*data-url=["']([^"']+)["'][^>]*data-server=["']([^"']*)["'][^>]*data-quality=["']([^"']*)["'][^>]*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(html).map {
                ServerButton(
                    url = it.groupValues[1],
                    server = it.groupValues[2],
                    quality = it.groupValues[3],
                )
            }.toList()

        val domButtons =
            document.select("button.server-btn[data-url]").map {
                ServerButton(
                    url = it.attr("data-url"),
                    server = it.attr("data-server"),
                    quality = it.attr("data-quality"),
                )
            }

        return (regexButtons + domButtons).distinctBy { "${it.server}|${it.url}" }
    }

    private fun String.extractBackgroundUrl(): String? {
        return Regex("""url\(['"]?([^'")]+)""").find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.toMinutes(): Int? {
        val parts = trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    private fun String.urlEncode(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    private fun String.urlDecode(): String {
        return URLDecoder.decode(this, "UTF-8")
    }

    private fun String?.toAbsoluteUrl(): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return value.toAbsoluteUrl(mainUrl)
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$baseUrl$value"
            else -> value
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

private data class ServerButton(
    val url: String,
    val server: String,
    val quality: String,
)

private data class VidsrcPlaylistItem(
    val url: String,
    val durationUs: Long,
)

    private val headers =
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        )
}
