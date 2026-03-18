package com.azmovies

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class Noxx : MainAPI() {
    override var mainUrl = "https://noxx.to"
    override var name = "Noxx😅"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage =
        mainPageOf(
            "" to "Latest",
            "s=rating" to "Top Rating",
            "s=alphabetical" to "A-Z",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val browseUrl = buildBrowseUrl(request.data, 1)
        if (page == 1) {
            val document = requestPage(browseUrl).document
            return newHomePageResponse(
                request.name,
                document.select("a.poster-card").mapNotNull { it.toSeriesSearchResult() },
            )
        }

        val verified = requestPage(browseUrl)
        val apiUrl = buildLoadMoreUrl(request.data, page)
        val response =
            app.get(
                apiUrl,
                referer = browseUrl,
                cookies = verified.cookies,
                headers =
                    mapOf(
                        "Accept" to "application/json,text/plain,*/*",
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
            )
        val data = tryParseJson<NoxxBrowseResponse>(response.text)
        val home = data?.series.orEmpty().mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = requestPage("$mainUrl/browse?q=${query.urlEncode()}").document
        return document.select("a.poster-card").mapNotNull { it.toSeriesSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = requestPage(url).document
        val title = document.selectFirst("section h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("section img[alt=\"$title\"]")?.attr("src").toAbsoluteUrl()
        val background = document.selectFirst("section img.backdrop-filter")?.attr("src").toAbsoluteUrl()
        val plot = document.selectFirst("section p.text-gray-300")?.text()?.trim()
        val rating =
            Regex(""""ratingValue"\s*:\s*"([^"]+)"""")
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)
        val year =
            Regex(""""datePublished"\s*:\s*"(\d{4})-\d{2}-\d{2}"""")
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        val tags =
            document
                .select("a[href^=\"/browse?g=\"]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val episodes =
            document
                .select("a.episode-card")
                .mapNotNull { episodeCard ->
                    val href = episodeCard.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
                    val (season, episode) = parseEpisodeNumbers(href) ?: return@mapNotNull null
                    val episodeTitle =
                        episodeCard.selectFirst(".episode-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                    newEpisode(href) {
                        this.name = episodeTitle ?: "Episode $episode"
                        this.season = season
                        this.episode = episode
                        this.posterUrl = episodeCard.selectFirst("img")?.attr("src").toAbsoluteUrl()
                        this.description = episodeCard.selectFirst(".episode-overview")?.text()?.trim()
                    }
                }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            addScore(rating)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = requestPage(data)
        val buttons = response.document.select("#serverselector button[value], button.sch[value]")
        val seenSubtitles = linkedSetOf<String>()

        buttons.forEach { button ->
            val rawUrl = button.attr("value").replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
            }

            runCatching {
                if (rawUrl.contains("vidsrc.xyz", true)) {
                    if (!loadVidsrc(rawUrl, callback)) {
                        loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }.onFailure {
                loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return buttons.isNotEmpty()
    }

    private suspend fun loadVidsrc(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = normalizeVidsrcUrl(url)
        val browserHeaders =
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to USER_AGENT,
            )
        val embedResponse = app.get(embedUrl, referer = "$mainUrl/", headers = browserHeaders)
        val twoEmbedHash = extractVidsrcServerHash(embedResponse.text, "2Embed") ?: return false
        val rcpUrl = "//cloudnestra.com/rcp/$twoEmbedHash".toAbsoluteUrl(getBaseUrl(embedResponse.url)) ?: return false
        val rcpResponse = app.get(rcpUrl, referer = "${getBaseUrl(embedResponse.url)}/", headers = browserHeaders)
        val srcrcpUrl =
            Regex("""/srcrcp/[^'"\s<]+""")
                .find(rcpResponse.text)
                ?.value
                ?.toAbsoluteUrl(getBaseUrl(rcpResponse.url))
                ?: return false
        val twoEmbedResponse = app.get(srcrcpUrl, referer = "${getBaseUrl(rcpResponse.url)}/", headers = browserHeaders)
        val xpsUrl =
            Regex("""https://streamsrcs\.2embed\.cc/xps(?:-tv)?\?[^'"\s<]+""", RegexOption.IGNORE_CASE)
                .find(twoEmbedResponse.text)
                ?.value
                ?: return false
        val xpsResponse = app.get(xpsUrl, referer = "${getBaseUrl(twoEmbedResponse.url)}/", headers = browserHeaders)
        val xpassSlug = xpsResponse.document.selectFirst("iframe#framesrc")?.attr("src")?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        val xpassBase = if (xpsUrl.contains("xps-tv", true)) "https://play.xpass.top/e/tv/" else "https://play.xpass.top/e/movie/"
        val xpassUrl = "$xpassBase${xpassSlug.removePrefix("/")}"
        val xpassResponse = app.get(xpassUrl, referer = "${getBaseUrl(xpsResponse.url)}/", headers = browserHeaders)
        val playlistUrl =
            Regex(""""playlist":"([^"]+/playlist\.json)"""")
                .find(xpassResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.toAbsoluteUrl(getBaseUrl(xpassResponse.url))
                ?: return false
        val playlistResponse =
            app.get(
                playlistUrl,
                referer = xpassUrl,
                headers = mapOf("Accept" to "application/json,text/plain,*/*", "User-Agent" to USER_AGENT),
            )
        val streamUrl =
            Regex(""""file"\s*:\s*"([^"]+)"""")
                .findAll(playlistResponse.text)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { it.decodeJsonUrl() }
                .firstOrNull { it.contains(".m3u8", true) }
                ?: return false
        val streamHeaders =
            mapOf(
                "Accept" to "*/*",
                "Referer" to "https://play.xpass.top/",
                "Origin" to "https://play.xpass.top",
                "User-Agent" to USER_AGENT,
            )
        val generated = M3u8Helper.generateM3u8("VidSrc", streamUrl, "https://play.xpass.top/", headers = streamHeaders)
        if (generated.isEmpty()) return false
        generated.forEach(callback)
        return true
    }

    private suspend fun requestPage(url: String): NiceResponse {
        val response = app.get(url, headers = headers, timeout = 30L)
        if (!response.isVerificationPage()) return response

        val token = Regex("""var token = "([^"]+)"""").find(response.text)?.groupValues?.getOrNull(1)
        if (token.isNullOrBlank()) return response
        val cookies = response.cookies

        val verifiedResponse =
            app.post(
            "$mainUrl/verified",
            data = mapOf("token" to token),
            cookies = cookies,
            headers =
                mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json,text/plain,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                ),
        )
        val verifiedCookies = if (verifiedResponse.cookies.isNotEmpty()) verifiedResponse.cookies else cookies

        return app.get(url, headers = headers, cookies = verifiedCookies, timeout = 30L)
    }

    private fun NiceResponse.isVerificationPage(): Boolean {
        return text.contains("Verifying your browser", true) && text.contains("var token =")
    }

    private fun buildBrowseUrl(query: String, page: Int): String {
        val base = "$mainUrl/browse"
        val params = query.takeIf { it.isNotBlank() }?.split("&")?.toMutableList() ?: mutableListOf()
        if (page > 1) params += "page=$page"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    private fun buildLoadMoreUrl(query: String, page: Int): String {
        val base = "$mainUrl/api/load-more-browse"
        val params = query.takeIf { it.isNotBlank() }?.split("&")?.toMutableList() ?: mutableListOf()
        params += "page=$page"
        return "$base?${params.joinToString("&")}"
    }

    private fun Element.toSeriesSearchResult(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        val title =
            selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: selectFirst("span")?.text()?.trim()
                ?: return null
        val poster = selectFirst("img")?.attr("src").toAbsoluteUrl()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun NoxxSeries.toSearchResult(): SearchResponse? {
        val slug = slug ?: return null
        val title = title ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/tv/$slug", TvType.TvSeries) {
            this.posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            this.year = firstAirDate?.substringBefore("-")?.toIntOrNull()
        }
    }

    private fun parseEpisodeNumbers(url: String): Pair<Int, Int>? {
        val match = Regex("""/tv/[^/]+/(\d+)/(\d+)""").find(url) ?: return null
        return match.groupValues[1].toIntOrNull()?.let { season ->
            match.groupValues[2].toIntOrNull()?.let { episode ->
                season to episode
            }
        }
    }

    private fun extractSubtitle(url: String): SubtitleFile? {
        val subtitleUrl = url.substringAfter("c1_file=", "").substringBefore("&").urlDecode()
        if (subtitleUrl.isBlank() || !subtitleUrl.contains(".vtt", true)) return null
        val label = url.substringAfter("c1_label=", "English").substringBefore("&").urlDecode()
        return SubtitleFile(label.ifBlank { "English" }, subtitleUrl)
    }

    private fun normalizeVidsrcUrl(url: String): String {
        if (url.contains("autoplay=", true)) return url
        val joiner = if (url.contains("?")) "&" else "?"
        return "$url${joiner}autoplay=1"
    }

    private fun extractVidsrcServerHash(
        html: String,
        serverName: String,
    ): String? {
        return Regex(
            """<div\s+class=["']server["'][^>]*data-hash=["']([^"']+)["'][^>]*>\s*${Regex.escape(serverName)}\s*</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
    }

    private fun String.decodeJsonUrl(): String {
        return replace("\\u0026", "&").replace("\\/", "/")
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

    private val headers =
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
}

private data class NoxxBrowseResponse(
    val success: Boolean? = null,
    val series: List<NoxxSeries>? = null,
    @JsonProperty("hasMore")
    val hasMore: Boolean? = null,
)

private data class NoxxSeries(
    val slug: String? = null,
    val title: String? = null,
    @JsonProperty("poster_path")
    val posterPath: String? = null,
    @JsonProperty("first_air_date")
    val firstAirDate: String? = null,
)
