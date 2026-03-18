package com.filmkita

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element

class Filmkita : MainAPI() {
    override var mainUrl = "https://s3.iix.llc"
    override var name = "Filmkita😐"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private var directUrl: String? = null

    override val mainPage =
        mainPageOf(
            "page/%d/" to "Film Terbaru",
            "tv/page/%d/" to "TV Series",
            "category/action/page/%d/" to "Action",
            "category/drama/page/%d/" to "Drama",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(fixUrl(request.data.format(page))).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        directUrl = getBaseUrl(response.url)
        val document = response.document

        val title =
            document
                .selectFirst("h1.entry-title")
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
            document
                .select("div.gmr-moviedata strong:contains(Year:) > a")
                .text()
                .trim()
                .toIntOrNull()
        val tvType =
            when {
                url.contains("/tv/", true) -> TvType.TvSeries
                document.selectFirst("div.vid-episodes a, div.gmr-listseries a") != null ->
                    TvType.TvSeries
                else -> TvType.Movie
            }
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating =
            document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors =
            document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map {
                it.select("a").text()
            }
        val duration =
            document
                .selectFirst("div.gmr-moviedata span[property=duration]")
                ?.text()
                ?.replace(Regex("\\D"), "")
                ?.toIntOrNull()
        val recommendations =
            (
                document.select("article.item.col-md-20").mapNotNull { it.toRecommendResult() } +
                    document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }
            ).distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes =
                document
                    .select("div.vid-episodes a, div.gmr-listseries a")
                    .map { eps ->
                        val href = fixUrl(eps.attr("href"))
                        val episodeName = eps.text().trim()
                        val episodeNumber = extractEpisodeNumber(episodeName, href)
                        val seasonNumber = extractSeasonNumber(episodeName, href)

                        newEpisode(href) {
                            name = episodeName
                            episode = episodeNumber
                            season = seasonNumber
                        }
                    }
                    .filter { it.episode != null }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrEmpty()) {
            document.select("ul.muvipro-player-tabs li a").amap { element ->
                val iframe =
                    app.get(fixUrl(element.attr("href"))).document
                        .selectFirst("div.gmr-embed-responsive iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                        ?: return@amap

                loadExtractor(iframe, "$directUrl/", subtitleCallback, callback)
            }
        } else {
            document.select("div.tab-content-ajax").amap { element ->
                val server =
                    app.post(
                            "$directUrl/wp-admin/admin-ajax.php",
                            data =
                                mapOf(
                                    "action" to "muvipro_player_content",
                                    "tab" to element.attr("id"),
                                    "post_id" to id,
                                ),
                        )
                        .document
                        .select("iframe")
                        .attr("src")
                        .let { httpsify(it) }

                loadExtractor(server, "$directUrl/", subtitleCallback, callback)
            }
        }

        document.select("ul.gmr-download-list li a").forEach { linkElement ->
            val downloadUrl = linkElement.attr("href")
            if (downloadUrl.isNotBlank()) {
                loadExtractor(downloadUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(selectFirst("h2.entry-title > a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val ratingText = selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val quality =
            select("div.gmr-qual, div.gmr-quality-item > a")
                .text()
                .trim()
                .replace("-", "")
        val isTvSeries =
            href.contains("/tv/", true) ||
                selectFirst("div.gmr-numbeps > span, span.episode") != null ||
                title.contains("episode", true) ||
                text().contains("TV SERIES", true)

        return if (isTvSeries) {
            val episode =
                Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: select("div.gmr-numbeps > span").text().toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title =
            selectFirst("h2.entry-title > a, a > span.idmuvi-rp-title")?.text()?.trim()
                ?: return null
        val href = fixUrl(selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(selectFirst("a > img")?.getImageAttr())?.fixImageQuality()
        val isTvSeries = href.contains("/tv/", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() } ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun extractEpisodeNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:eps?|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: Regex("""\b(\d+)\b""").find(name)?.groupValues?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun extractSeasonNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:^|\b)s(?:eason)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-season-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
        )?.toIntOrNull()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
