package com.fufafilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.toNewSearchResponseList
import java.net.URI
import org.jsoup.nodes.Element

class Fufafilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://fufafilm.cyou"
    private var directUrl: String? = null
    override var name = "Fufafilm🍁"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    

    override val mainPage =
            mainPageOf(
                "category/movie/page/%d/" to "Semua Film",
                "category/moviebox/page/%d/" to "Trending Minggu Ini",
                "country/indonesia/page/%d/" to "Film Indonesia",
                "tv/page/%d/" to "Series Unggulan",
                "/page/%d/?s=&search=advanced&post_type=movie&index=&orderby=date&genre=animation" to
                            "Animasi",
                "category/superhero/page/%d/" to "Superhero",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
    val data = request.data.format(page)
    val document = app.get("$mainUrl/$data").document
    val home = document.select("article.item").mapNotNull { it.toSearchResult() }
    return newHomePageResponse(request.name, home)
}

private fun Element.toSearchResult(): SearchResponse? {
    val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
    val href = fixUrl(this.selectFirst("a")!!.attr("href"))
    val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()

    val quality = this.select("div.gmr-qual, div.gmr-quality-item > a")
        .text().trim().replace("-", "")

    // 🔹 Ambil rating (contoh: "5.9")
    val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()

    return if (quality.isEmpty()) {
        val episode = Regex("Episode\\s?([0-9]+)")
            .find(title)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()

        newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            addSub(episode)
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    } else {
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)

            // 🔹 Tambahkan score rating
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }
}    


    override suspend fun search(query: String, page: Int): SearchResponseList? {
		val document = app.get("$mainUrl/page/$page/?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
		val results = document.select("article.has-post-thumbnail").mapNotNull { it.toSearchResult() }.toNewSearchResponseList()
		return results
	}

        private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

        override suspend fun load(url: String): LoadResponse {
    // Pakai Desktop User-Agent agar website tidak mengirim halaman mobile
    val desktopHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    val fetch = app.get(url, headers = desktopHeaders)
    val document = fetch.document

    val title =
        document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.trim()
            .orEmpty()

    val poster =
        fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()

    val tags = document.select("strong:contains(Genre) ~ a").eachText()

    val year =
        document.select("div.gmr-moviedata strong:contains(Year:) > a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

    val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
    val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
    val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
    val rating =
        document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()?.trim()

    val actors =
        document.select("div.gmr-moviedata").last()
            ?.select("span[itemprop=actors]")?.map {
                it.select("a").text()
            }

    val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
        ?.text()
        ?.replace(Regex("\\D"), "")
        ?.toIntOrNull()

    val recommendations = document
    .select("article.item.col-md-20")
    .mapNotNull { it.toRecommendResult() }


    // =========================
    //  MOVIE
    // =========================

    if (tvType == TvType.Movie) {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            this.recommendations = recommendations
            this.duration = duration ?: 0
            addTrailer(trailer)
        }
    }


    // =========================
    //  TV SERIES MODE
    // =========================

    // Tombol “View All Episodes” → URL halaman series
    val seriesUrl =
        document.selectFirst("a.button.button-shadow.active")?.attr("href")
            ?: url.substringBefore("/eps/")

    val seriesDoc = app.get(seriesUrl, headers = desktopHeaders).document

    val episodeElements =
        seriesDoc.select("div.gmr-listseries a.button.button-shadow")

    // Nomor episode manual (agar tidak lompat)
    var episodeCounter = 1

    val episodes = episodeElements.mapNotNull { eps ->
        val href = fixUrl(eps.attr("href")).trim()
        val name = eps.text().trim()

        // Skip tombol "View All Episodes"
        if (name.contains("View All Episodes", ignoreCase = true)) return@mapNotNull null

        // Skip jika href sama dengan halaman series
        if (href == seriesUrl) return@mapNotNull null

        // Skip elemen non-episode
        if (!name.contains("Eps", ignoreCase = true)) return@mapNotNull null

        // Ambil season (default 1)
        val regex = Regex("""S(\d+)\s*Eps""", RegexOption.IGNORE_CASE)
        val match = regex.find(name)
        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        // Nomor episode final
        val epNum = episodeCounter++

        newEpisode(href) {
            this.name = name
            this.season = season
            this.episode = epNum
        }
    }

    // Return response TV Series
    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.year = year
        this.plot = description
        this.tags = tags
        addScore(rating)
        addActors(actors)
        this.recommendations = recommendations
        this.duration = duration ?: 0
        addTrailer(trailer)
    }
}

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").amap { ele ->
                val iframe =
                        app.get(fixUrl(ele.attr("href")))
                                .document
                                .selectFirst("div.gmr-embed-responsive iframe")
                                .getIframeAttr()
                                ?.let { httpsify(it) }
                                ?: return@amap

                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").amap { ele ->
                val server =
                        app.post(
                                        "$directUrl/wp-admin/admin-ajax.php",
                                        data =
                                                mapOf(
                                                        "action" to "muvipro_player_content",
                                                        "tab" to ele.attr("id"),
                                                        "post_id" to "$id"
                                                )
                                )
                                .document
                                .select("iframe")
                                .attr("src")
                                .let { httpsify(it) }

                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
