package com.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class Klikxxi : MainAPI() {
    override var mainUrl = "https://klikxxi.fit"
    override var name = "Klikxxi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=&page=%d" to "Update Terbaru",
        "$mainUrl/category/western-series/page/%d/" to "Western Series",
        "$mainUrl/category/india-series/page/%d/" to "India Series",
        "$mainUrl/category/korea/page/%d/" to "Korea Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val document = app.get(url).document

        val items = document.select("main#main article, div.gmr-box-content")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("ul.page-numbers li a.next") != null
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a[href][title]") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title")
            .removePrefix("Permalink to: ")
            .ifBlank { linkElement.text() }
            .trim()
        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        var poster = when {
            imgElement?.hasAttr("data-lazy-src") == true -> imgElement.attr("abs:data-lazy-src")
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("abs:data-src")
            imgElement?.hasAttr("data-lazy-srcset") == true -> imgElement.attr("abs:data-lazy-srcset").substringBefore(" ")
            imgElement?.hasAttr("srcset") == true -> imgElement.attr("abs:srcset").substringBefore(" ")
            else -> imgElement?.attr("abs:src")
        }

        // Hapus ukuran kecil -170x255, -152x228, dll agar dapat gambar full
        poster = poster?.replace(Regex("-\\d+x\\d+(?=\\.\\w{3,4})"), "")

        val quality = this.selectFirst("span.gmr-quality-item")?.text()?.trim()
        val typeText = this.selectFirst(".gmr-posttype-item")?.text()?.trim()
        val isSeries = typeText.equals("TV Show", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                if (!quality.isNullOrBlank()) addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item-infinite, div.gmr-box-content")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, div.mvic-desc h3")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()

        val poster = document.selectFirst("figure.pull-left img, div.gmr-movieposter img, .poster img")
            ?.let { img ->
                img.attr("data-lazy-src")?.let { fixUrlNull(it) }
                    ?: img.attr("src")?.let { fixUrlNull(it) }
            }

        val description = document.selectFirst("div[itemprop=description] > p, div.desc p.f-desc, div.entry-content > p")
            ?.text()
            ?.trim()

        val tags = document.select("div.gmr-moviedata strong:contains(Genre:) > a")
            .map { it.text() }

        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a")
            .text()
            .toIntOrNull()

        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")
            ?.attr("href")

        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()
            ?.toRatingInt()

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val recommendations = document.select("div.gmr-related-post article, div.related-post article")
            .mapNotNull { it.toSearchResult() }

        val episodesElements = document.select("div.vid-episodes a, div.gmr-listseries a")
        val tvType = if (episodesElements.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            val episodes = episodesElements.mapIndexedNotNull { index, epLink ->
                val href = epLink.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return@mapIndexedNotNull null
                val name = epLink.text().trim()
                val season = Regex("S(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("E(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(href) {
                    this.name = name
                    this.season = season ?: 1
                    this.episode = episode ?: (index + 1)
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.rating = rating
                addActors(actors)
                addTrailer(trailer)
                this.recommendations = recommendations
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
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
        if (postId.isNullOrBlank()) return false

        document.select("div.tab-content-ajax").forEach { tab ->
            val tabId = tab.attr("id")
            if (tabId.isNullOrBlank()) return@forEach

            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to postId
                )
            ).document

            val iframe = response.selectFirst("iframe")?.attr("src") ?: return@forEach
            val link = httpsify(iframe)
            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }
        return true
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}