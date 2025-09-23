package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

class Ngefilm : MainAPI() {
    override var mainUrl = "https://new18.ngefilm.site"
    override var name = "Ngefilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Upload Terbaru",
        "$mainUrl/?s=&search=advanced&post_type=tv" to "Semua Series",
        "$mainUrl/?s=&search=advanced&post_type=tv&genre=drama&country=korea" to "Drama Korea",
        "$mainUrl/?s=&search=advanced&post_type=tv&country=indonesia" to "Series Indonesia",
        "$mainUrl/country/indonesia/" to "Film Indonesia",
    )

    // ambil list di mainpage
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val doc = app.get(request.data).document
    val items = doc.select("article.has-post-thumbnail").mapNotNull { it.toSearchResult() }
    return newHomePageResponse(request.name, items)
}

private fun Element.toSearchResult(): SearchResponse? {
    val link = this.selectFirst("a") ?: return null
    val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
    val poster = fixUrlNull(this.selectFirst("img")?.getImageAttr())
    val qualityText = this.selectFirst(".gmr-quality-item, .mli-quality")?.text()?.trim()

    return newMovieSearchResponse(title, link.attr("href"), TvType.Movie) {
        this.posterUrl = poster
        this.quality = getQualityFromString(qualityText) // pakai helper biar aman
    }
}



    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

   override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(doc.selectFirst(".thumb img")?.getImageAttr())

        val rawPlot = doc.selectFirst("div.entry-content.entry-content-single p")?.text()?.trim()
        val plot = rawPlot?.substringBefore("Oleh:")?.trim()

        val year = doc.selectFirst("span[itemprop=dateCreated]")?.text()?.toIntOrNull()
        val type = if (doc.select("div.gmr-listseries a").isNotEmpty()) TvType.TvSeries else TvType.Movie

        val trailer = doc.selectFirst("a.gmr-trailer-popup")?.attr("href")
            ?: doc.selectFirst("div.gmr-embed-responsive iframe")?.attr("src")

        return when (type) {
            TvType.Movie -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    addTrailer(trailer)
                }
            }
            TvType.TvSeries -> {
                val episodes = doc.select("div.gmr-listseries a")
                    .filter { !it.text().contains("Pilih", ignoreCase = true) }
                    .mapIndexed { idx, el ->
                        newEpisode(fixUrl(el.attr("href"))) {
                            this.name = el.text().ifBlank { "Episode ${idx + 1}" }
                            this.season = null
                            this.episode = idx + 1
                        }
                    }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                }
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val doc = app.get(data).document

    // gabung iframe langsung + server links
    val allLinks = buildList {
        addAll(doc.select("d").map { fixUrl(it.attr("href")) })
        addAll(doc.select("ul.muvipro-player-tabs li a, div.gmr-embed-responsive iframe").map { fixUrl(it.attr("href")) })
    }

    allLinks.forEach { link ->
        val serverDoc = runCatching { app.get(link).document }.getOrNull()
        val iframe = serverDoc?.selectFirst("iframe")?.getIframeAttr() ?: link
        if (!iframe.isNullOrBlank()) {
            loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
        }
    }

    return true
}


    // Helpers
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

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
