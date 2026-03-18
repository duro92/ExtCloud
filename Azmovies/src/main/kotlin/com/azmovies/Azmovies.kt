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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        val document = request(data).document
        val seenSubtitles = linkedSetOf<String>()

        document.select("button.server-btn[data-url]").forEach { button ->
            val rawUrl = button.attr("data-url").replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(subtitle)
                }
            }

            val label = buildString {
                append(button.attr("data-server").ifBlank { "Server" })
                val quality = button.attr("data-quality").trim()
                if (quality.isNotBlank()) {
                    append(" ")
                    append(quality)
                }
            }.trim()

            runCatching {
                loadExtractor(rawUrl, mainUrl, subtitleCallback, callback)
            }.onFailure {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank { name },
                        url = rawUrl,
                        type = com.lagradost.cloudstream3.utils.INFER_TYPE,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(button.attr("data-quality"))
                    },
                )
            }
        }

        return true
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

        app.post(
            "$mainUrl/verified",
            headers = headers + mapOf("Content-Type" to "application/json"),
            requestBody =
                mapOf("token" to token)
                    .toJson()
                    .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
        )

        return app.get(
            url,
            headers = headers,
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
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> value
        }
    }

    private val headers =
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        )
}
