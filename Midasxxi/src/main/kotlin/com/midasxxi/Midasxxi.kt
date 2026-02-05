package com.midasxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Midasxxi : MainAPI() {
    override var mainUrl = "https://ssstik.tv"
    override var name = "MidasXXI"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies/page/%d/" to "Movies",
        "tvshows/page/%d/" to "TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data.replace("page/%d/", "")}"
        } else {
            "$mainUrl/${request.data.format(page)}"
        }.replace("//", "/").replace(":/", "://")

        val document = app.get(url).document
        val expectedType =
            if (request.data.contains("tvshows", ignoreCase = true)) TvType.TvSeries else TvType.Movie

        val items = document.select(
            "article, div.ml-item, div.item, div.movie-item, div.film, div.item-infinite"
        ).mapNotNull { it.toSearchResult(expectedType) }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(expectedType: TvType? = null): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = fixUrl(link.attr("href"))

        val title = link.attr("title")
            .removePrefix("Permalink to:")
            .ifBlank {
                selectFirst("h1, h2, h3, h4, .title, .movie-title, .entry-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }
            .trim()

        if (title.isBlank()) return null

        val posterUrl = selectFirst("img")?.fixPoster()?.let { fixUrl(it) }
        val quality = selectFirst(".quality, .gmr-quality-item, .gmr-qual, .q")
            ?.text()
            ?.trim()
            ?.replace("-", "")
            ?.takeIf { it.isNotBlank() }

        val inferredType =
            expectedType ?: when {
                href.contains("/tvshow", ignoreCase = true) -> TvType.TvSeries
                href.contains("/tvshows", ignoreCase = true) -> TvType.TvSeries
                selectFirst(".type-tv, .tv, .series") != null -> TvType.TvSeries
                else -> TvType.Movie
            }

        return if (inferredType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, div.ml-item, div.item, div.movie-item, div.film")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title, h1, .mvic-desc h3, .title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()

        val poster = document
            .selectFirst(".sheader .poster img, figure.pull-left img, .poster img, .mvic-thumb img, img.wp-post-image, img")
            ?.fixPoster()
            ?.let { fixUrl(it) }

        val description = document
            .selectFirst("div[itemprop=description] > p, .wp-content > p, .entry-content > p, .desc p, .synopsis")
            ?.text()
            ?.trim()

        val tags = document.select("strong:contains(Genre) ~ a, .sgeneros a, .wp-tags a, .genre a, .genres a").eachText()

        val year = document
            .selectFirst("strong:contains(Year) ~ a, .year, .release")
            ?.text()
            ?.trim()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val rating = document
            .selectFirst("span[itemprop=ratingValue], .dt_rating_vgs, .rating, .imdb")
            ?.text()
            ?.trim()

        val episodes = parseEpisodes(document)
        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (!rating.isNullOrBlank()) addScore(rating, 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (!rating.isNullOrBlank()) addScore(rating, 10)
            }
        }
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<Episode> {
        val containers = document.select(
            "div.episode-list, div.episodes, ul.episodes, div#episodes, div.eplister, div.seasons"
        )
        val links = if (containers.isNotEmpty()) {
            containers.select("a[href]")
        } else {
            document.select("a[href*=\"episode\"], a[href*=\"/eps\"], a[href*=\"/ep\"], a.episode")
        }

        val episodes = links.mapIndexedNotNull { index, a ->
            val href = a.attr("href").trim()
                .takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: return@mapIndexedNotNull null

            val name = a.text().trim().ifBlank { "Episode ${index + 1}" }

            val epNum = Regex("E(p|ps)?\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(name)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: (index + 1)

            newEpisode(href) {
                this.name = name
                this.season = 1
                this.episode = epNum
            }
        }

        return episodes.distinctBy { it.data }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // DooPlay player (umum di tema dooplay / midasxxi)
        val dooPlayer = document.selectFirst("div.dooplay_player[data-post][data-nume][data-type]")
        if (dooPlayer != null) {
            val postId = dooPlayer.attr("data-post")
            val nume = dooPlayer.attr("data-nume")
            val type = dooPlayer.attr("data-type")

            if (postId.isNotBlank() && nume.isNotBlank() && type.isNotBlank()) {
                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    )
                ).document

                response.select("iframe").forEach { iframe ->
                    val link = iframe.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
                return true
            }
        }

        // DooPlay options list (li.dooplay_player_option)
        val options = document.select("li.dooplay_player_option[data-post][data-nume][data-type]")
        if (options.isNotEmpty()) {
            options.forEach { opt ->
                val postId = opt.attr("data-post")
                val nume = opt.attr("data-nume")
                val type = opt.attr("data-type")
                if (postId.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    )
                ).document

                response.select("iframe").forEach { iframe ->
                    val link = iframe.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }

        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (!postId.isNullOrBlank()) {
            document.select("div.tab-content-ajax").forEach { tab ->
                val tabId = tab.attr("id")
                if (tabId.isBlank()) return@forEach

                val response = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to postId
                    )
                ).document

                val iframe = response.selectFirst("iframe")?.getIframeAttr() ?: return@forEach
                loadExtractor(httpsify(iframe), mainUrl, subtitleCallback, callback)
            }
            return true
        }

        // Fallback: langsung dari iframe di halaman
        document.select("div.pframe iframe, .dooplay_player iframe, iframe").forEach { iframe ->
            val link = iframe.getIframeAttr()?.let { httpsify(it) } ?: return@forEach
            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    private fun Element?.getIframeAttr(): String? {
        if (this == null) return null
        return this.attr("data-litespeed-src").takeIf { it.isNotBlank() }
            ?: this.attr("data-src").takeIf { it.isNotBlank() }
            ?: this.attr("src")
    }

    private fun Element?.fixPoster(): String? {
        if (this == null) return null

        if (this.hasAttr("srcset")) {
            val srcset = this.attr("srcset").trim()
            val best = srcset.split(",")
                .map { it.trim().split(" ")[0] }
                .lastOrNull()
            if (!best.isNullOrBlank()) return best.fixImageQuality()
        }

        val dataSrc = when {
            this.hasAttr("data-lazy-src") -> this.attr("data-lazy-src")
            this.hasAttr("data-src") -> this.attr("data-src")
            else -> null
        }
        if (!dataSrc.isNullOrBlank()) return dataSrc.fixImageQuality()

        val src = this.attr("src")
        return if (src.isNotBlank()) src.fixImageQuality() else null
    }

    private fun String.fixImageQuality(): String {
        val regex = Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return this.replace(regex, "")
    }

}
