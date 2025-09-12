package com.layarKacaProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://tv6.lk21official.cc/"
    private var seriesUrl = "https://tv1.nontondrama.my/"
    private var searchUrl = "https://search.lk21.party"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terpopuler",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "$seriesUrl/latest-series/page/" to "Series Terbaru",
        "$seriesUrl/series/asian/page/" to "Film Asian Terbaru",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article figure").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getProperLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        val res = app.get(url).document
        return if (res.select("title").text().contains("Nontondrama", true)) {
            res.selectFirst("a#openNow")?.attr("href")
                ?: res.selectFirst("div.links a")?.attr("href")
                ?: url
        } else {
            url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.ownText()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val type = if (this.selectFirst("span.episode") == null) TvType.Movie else TvType.TvSeries

        return if (type == TvType.TvSeries) {
            val episode = this.selectFirst("span.episode strong")?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            val quality = this.select("div.quality").text().trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$searchUrl/search.php?s=$query").text
        val results = mutableListOf<SearchResponse>()

        val root = JSONObject(res)
        val arr = root.getJSONArray("data")

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = item.getString("title")
            val slug = item.getString("slug")
            val type = item.getString("type")
            val posterUrl = "https://poster.lk21.party/wp-content/uploads/${item.optString("poster")}"

            when (type) {
                "series" -> results.add(
                    newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                )
                "movie" -> results.add(
                    newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = getProperLink(url)
        val document = app.get(fixedUrl).document
        val baseUrl = fetchURL(fixedUrl)

        val title = document.selectFirst("div.movie-info h1")?.text()?.trim().orEmpty()
        val poster = document.select("meta[property=og:image]").attr("content")
        val tags = document.select("div.tag-list span").map { it.text() }

        val year = Regex("\\d, (\\d+)")
            .find(document.select("div.movie-info h1").text().trim())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()

        val tvType = if (document.selectFirst("#season-data") != null) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.meta-info")?.text()?.trim()
        val trailer = document.selectFirst("ul.action-left > li:nth-child(3) > a")?.attr("href")

        // convert ke float dan kali 10 biar sesuai skala 0–100
        val rating = document.selectFirst("div.info-tag strong")?.text()?.toFloatOrNull()
        val score = rating?.times(10)

        val recommendations = document.select("li.slider article").map {
            val recName = it.selectFirst("h3")?.text()?.trim().orEmpty()
            val recHref = baseUrl + it.selectFirst("a")!!.attr("href")
            val recPosterUrl = fixUrl(it.selectFirst("img")?.attr("src").orEmpty())
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val json = document.selectFirst("script#season-data")?.data()
            val episodes = mutableListOf<Episode>()

            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val href = fixUrl("$baseUrl/${ep.getString("slug")}")
                        val episodeNo = ep.optInt("episode_no")
                        val seasonNo = ep.optInt("s")

                        episodes.add(
                            newEpisode(href) {
                                this.name = "Episode $episodeNo"
                                this.season = seasonNo
                                this.episode = episodeNo
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = score
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("ul#player-list > li").map { fixUrl(it.select("a").attr("href")) }.amap {
            val iframeUrl = it.getIframe()
            Log.d("Phisher", iframeUrl)
            loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun String.getIframe(): String {
        return app.get(this, referer = "$seriesUrl/").document
            .select("div.embed iframe")
            .attr("src")
    }

    private suspend fun fetchURL(url: String): String {
        val res = app.get(url, allowRedirects = false)
        val href = res.headers["location"]

        return if (href != null) {
            val it = URI(href)
            "${it.scheme}://${it.host}"
        } else {
            url
        }
    }
}
