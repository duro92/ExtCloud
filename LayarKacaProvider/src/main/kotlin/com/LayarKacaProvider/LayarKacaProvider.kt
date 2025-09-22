package com.layarKacaProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://lk21.de"
    private var seriesUrl = "https://series.lk21.de"
    private var searchurl= "https://search.lk21.party"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terplopuler",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "$seriesUrl/latest-series/page/" to "Series Terbaru",
        "$seriesUrl/series/asian/page/" to "Film Asian Terbaru",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article figure").mapNotNull {
            it.toSearchResult()
        }
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
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val type =
            if (this.selectFirst("span.episode") == null) TvType.Movie else TvType.TvSeries
        return if (type == TvType.TvSeries) {
            val episode = this.selectFirst("span.episode strong")?.text()?.filter { it.isDigit() }
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
        val res = app.get("$searchurl/search.php?s=$query").text
        val results = mutableListOf<SearchResponse>()

        val root = JSONObject(res)
        val arr = root.getJSONArray("data")

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = item.getString("title")
            val slug = item.getString("slug")
            val type = item.getString("type")
            val posterUrl = "https://poster.lk21.party/wp-content/uploads/"+item.optString("poster")
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
        val fixUrl = getProperLink(url)
        val document = app.get(fixUrl).document
        val titleFull = document.selectFirst("div.movie-info h1")?.text()?.trim().orEmpty()
        val title = titleFull.substringBefore("(").trim()
        val year = Regex("\\((\\d{4})\\)").find(titleFull)?.groupValues?.get(1)?.toIntOrNull()

        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.synopsis")?.text()
        val tags = document.select("div.tag-list a").map { it.text() }
        val rating = document.selectFirst("div.info-tag strong")?.text()?.toRatingInt()
        val trailer = document.selectFirst("ul.action-left a.yt-lightbox")?.attr("href")
            ?: document.selectFirst("div.trailer-series iframe")?.attr("src")

        val recommendations = document.select("li.slider article").map {
            val recName = it.selectFirst("h3")?.text()?.trim().orEmpty()
            val recHref = fixUrl(it.selectFirst("a")!!.attr("href"))
            val recPosterUrl = fixUrl(it.selectFirst("img")?.attr("src").orEmpty())
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (document.selectFirst("#season-data") != null) {
            // Series
            val episodes = mutableListOf<Episode>()
            val json = document.selectFirst("script#season-data")?.data()
            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val epUrl = fixUrl(ep.getString("slug"))
                        val episodeNo = ep.optInt("episode_no")
                        val seasonNo = ep.optInt("s")
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = ep.optString("title", "Episode $episodeNo")
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
                this.rating = rating
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            // Movie
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
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
        val doc = app.get(data).document

        // ambil semua link player
        val players = doc.select("ul#player-list li a").mapNotNull {
            it.attr("data-url").ifBlank { it.attr("href") }
        }.ifEmpty {
            listOfNotNull(doc.selectFirst("div.main-player")?.attr("data-iframe_url"))
        }

        val blacklist = listOf("short.icu", "abbys")

        players.amap { link ->
            if (blacklist.any { link.contains(it, ignoreCase = true) }) {
                Log.d("Layarkaca", "Skip blacklisted: $link")
                return@amap
            }
            Log.d("Layarkaca", "Process: $link")
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return true
    }
}
