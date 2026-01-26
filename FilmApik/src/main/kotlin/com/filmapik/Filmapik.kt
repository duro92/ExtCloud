package com.filmapik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Filmapik : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://filmapik.fitness"
    override var name = "FilmApik🎃"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "tvshows/page/%d/" to "Serial Terbaru",
        "latest/page/%d/" to "Film Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val url = "$mainUrl/${request.data.format(page)}"
        val document = app.get(url).document
        val items = document.select("div.items.normal article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
    val a = selectFirst("div.data h3 a") ?: return null
    val title = a.ownText().trim()
    val href = fixUrl(a.attr("href"))
    val poster = fixUrlNull(selectFirst("img[src]")?.attr("src"))
        ?.fixImageQuality()
    val rating = selectFirst("div.rating")
        ?.ownText()?.trim()?.toDoubleOrNull()
    val quality = selectFirst("span.quality")?.text()?.trim()
    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = poster
        if (!quality.isNullOrBlank()) addQuality(quality)
        rating?.let { this.score = Score.from10(it) }
    }
}


    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val img = a.selectFirst("img[src][alt]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = img.attr("alt").trim()
        val poster = fixUrlNull(img.attr("src")).fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document
    val title = document.selectFirst(
    "h1[itemprop=name], .sheader h1, .sheader h2"
)?.text()
    ?.replace(Regex("(?i)^nonton\\s+film\\s+"), "")
    ?.replace(Regex("(?i)subtitle\\s+indonesia.*$"), "")
    ?.trim()
    ?: document.selectFirst("#info h2")?.text()
        ?.replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        ?.replace(Regex("(?i)subtitle\\s+indonesia.*$"), "")
        ?.trim()
    ?: ""
    val poster = document.selectFirst(".sheader .poster img")
        ?.attr("src")
        ?.let { fixUrl(it) }
    val tags = document.select("span.sgeneros a")
    .map { it.text().trim() }
    val actors = document.select(".info-more span.tagline")
    .firstOrNull {
        it.text().contains("Actors", true) ||
        it.text().contains("Stars", true)
    }
    ?.select("a")
    ?.map { it.text() }
    ?: emptyList()
    val description = document.selectFirst(
        "div[itemprop=description], .wp-content, .entry-content, .desc, .entry"
    )?.text()?.trim()
        ?: "Tidak ada deskripsi."
    val year = document.selectFirst("#info .info-more .country a")
        ?.text()
        ?.toIntOrNull()
    val rating = document.selectFirst("#repimdb strong")
        ?.text()
        ?.toFloatOrNull()
    val recommendations = document
        .select("#single_relacionados article")
        .mapNotNull { it.toRecommendResult() }
    val duration = document.selectFirst("span.runtime")
    ?.text()
    ?.let { Regex("(\\d+)").find(it)?.value }
    ?.toIntOrNull()

    val seasonBlocks = document.select("#seasons .se-c")

    if (seasonBlocks.isNotEmpty()) {
        val episodes = mutableListOf<Episode>()

        seasonBlocks.forEach { block ->
            val seasonNum = block
                .selectFirst(".se-q .se-t")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
                ?: 1

            block.select(".se-a ul.episodios li a")
                .forEachIndexed { index, ep ->

                    val epUrl = fixUrl(ep.attr("href"))
                    val epName = ep.text().ifBlank {
                        "Episode ${index + 1}"
                    }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = index + 1
                        }
                    )
                }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.year = year
            addActors(actors)
            this.plot = description
            this.tags = tags
            this.duration = duration ?: 0
            this.recommendations = recommendations
            this.score = Score.from10(rating)
        }
    }

    val playUrl = document
        .selectFirst("#clickfakeplayer, .fakeplayer a")
        ?.attr("href")
        ?.let { fixUrl(it) }

    return newMovieLoadResponse(
    title,
    playUrl ?: url,
    TvType.Movie,
    playUrl ?: url   
) {
    this.posterUrl = poster
    this.year = year
    addActors(actors)
    this.plot = description
    this.tags = tags
    this.duration = duration ?: 0
    this.recommendations = recommendations
    this.score = Score.from10(rating)
}
}


    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val doc = app.get(data).document

    val links = mutableListOf<String>()

  
    doc.select("div.pframe iframe[src]").forEach { iframe ->
        val iframeUrl = fixUrl(iframe.attr("src"))
        loadExtractor(iframeUrl, data, subtitleCallback, callback)
    }
    doc.select("li.dooplay_player_option[data-url]").forEach { el ->
        val serverUrl = el.attr("data-url").trim()
        if (serverUrl.isNotEmpty()) {
            loadExtractor(serverUrl, data, subtitleCallback, callback)
        }
    }

    doc.select("div#download a.myButton[href]").forEach { a ->
        val href = a.attr("href").trim()
        if (href.isNotEmpty()) {
            links.add(fixUrl(href))
        }
    }

   
    for (raw in links) {
        val resolved = resolveIframe(raw)
        loadExtractor(resolved, data, subtitleCallback, callback)
    }

    return true
}



private suspend fun resolveIframe(url: String): String {

    val res = app.get(url, allowRedirects = true)

    val doc = res.document

    doc.selectFirst("iframe[src]")?.attr("src")?.trim()?.let {

        if (it.startsWith("http")) return it

    }

    doc.select("meta[http-equiv=refresh]").forEach { meta ->

        meta.attr("content")?.substringAfter("URL=")?.trim()?.let { refreshUrl ->

            if (refreshUrl.startsWith("http")) return resolveIframe(refreshUrl)

        }

    }

    val scripts = doc.select("script").html()

    val regexJs = Regex("""location\.href\s*=\s*["'](.*?)["']""")

    val match = regexJs.find(scripts)

    if (match != null) {

        val jsUrl = match.groupValues[1]

        if (jsUrl.startsWith("http")) return resolveIframe(jsUrl)

    }

    return res.url

}


    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.firstOrNull()
        return if (match != null) this.replace(match, "") else this
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl$url"
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}