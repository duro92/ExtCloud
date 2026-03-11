package com.Funmovieslix

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URL


class Funmovieslix : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://funmovieslix.com"
    override var name = "Funmovieslix🎥"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)


    override val mainPage = mainPageOf(
        "latest-updates" to "Update Terbaru",
        "order-by-title" to "Film A-Z",
        "category/action" to "Action Category",
        "category/science-fiction" to "Sci-Fi Category",
        "category/drama" to "Drama Category",
        "category/kdrama" to "KDrama",
        "category/crime" to "Crime Category",
        "category/fantasy" to "Fantasy Category",
        "category/mystery" to "Mystery Category",
        "category/comedy" to "Comedy Category",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
    val document = if (request.data == "latest-updates") {
        val url = if (page == 1)
            "$mainUrl/latest-updates/"
        else
            "$mainUrl/latest-updates/page/$page/"
        app.get(url).documentLarge
    } else {
        app.get("$mainUrl/${request.data}/page/$page").documentLarge
    }

    val home = if (request.data == "latest-updates") {
    document.select("#latest-wrap div.latest-card")
        .mapNotNull { it.toSearchResult() }
} else {
    document.select("#gmr-main-load div.movie-card")
        .mapNotNull { it.toSearchResult() }
}

    return newHomePageResponse(
        HomePageList(request.name, home, false),
        hasNext = true
    )
}

    private fun Element.toSearchResult(): SearchResponse? {

    val anchor = selectFirst(".overlay a") ?: selectFirst("a")
        ?: return null

    val href = fixUrl(anchor.attr("href"))
    if (href.isBlank()) return null

    val title = anchor.selectFirst("h3")?.text()
        ?: return null

    val img = selectFirst("img")

    val posterUrl = img?.let {
        val srcSet = it.attr("srcset")
        val bestUrl = if (srcSet.isNotBlank()) {
            srcSet.split(",")
                .map { s -> s.trim() }
                .maxByOrNull {
                    it.substringAfterLast(" ")
                        .removeSuffix("w")
                        .toIntOrNull() ?: 0
                }
                ?.substringBefore(" ")
        } else {
            it.attr("src")
        }
        fixUrlNull(bestUrl)
    }

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
        this.quality = getSearchQuality(this@toSearchResult)
    }
}


    override suspend fun search(query: String): List<SearchResponse> {
            val document = app.get("${mainUrl}?s=$query").document
            val results =document.select("#gmr-main-load div.movie-card").mapNotNull { it.toSearchResult() }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title =document.select("meta[property=og:title]").attr("content").substringBefore("(").substringBefore("-").trim()
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("div.desc-box p,div.entry-content p").text()
        val actors=document.select("div.cast-grid a").map { it.text() }
        val type = if (url.contains("tv")) TvType.TvSeries else TvType.Movie
        val trailer = document.select("meta[itemprop=embedUrl]").attr("content")
        val genre = document.select("div.gmr-moviedata:contains(Genre) a,span.badge").map { it.text() }
        val year =document.select("div.gmr-moviedata:contains(Year) a").text().toIntOrNull()
        val recommendation = document.select("div.movie-grid div").mapNotNull {
            val recName = it.select("p").text()
            val recHref = it.select("a").attr("href")
            val img = it.selectFirst("img")
            val srcSet = img?.attr("srcset").orEmpty()
            val bestUrl = if (srcSet.isNotBlank()) {
                srcSet.split(",")
                    .map { s -> s.trim() }
                    .maxByOrNull { s -> s.substringAfterLast(" ").removeSuffix("w").toIntOrNull() ?: 0 }
                    ?.substringBefore(" ")
            } else {
                img?.attr("src")
            }
            val recPosterUrl = fixUrlNull(bestUrl?.replace(Regex("-\\d+x\\d+"), ""))
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.gmr-listseries a").forEach { info ->
                    if (info.text().contains("All episodes", ignoreCase = true)) return@forEach
                    val text=info.text()
                    val season = Regex("S(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    val ep=Regex("Eps(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    val name = "Episode $ep"
                    val href = info.attr("href")
                    episodes.add(
                        newEpisode(href)
                        {
                            this.episode=ep
                            this.name=name
                            this.season=season
                        }
                    )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations=recommendation
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations=recommendation
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

        val scriptContent = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("const embeds") }
            ?: return false

        val regex = Regex("""https:\\/\\/[^"']+|https://[^"']+""")
        val urls = regex.findAll(scriptContent)
            .map { it.value.replace("\\/", "/").replace("\\", "") }
            .distinct()
            .toList()

        val queue = ArrayDeque<Pair<String, Int>>()
        urls.forEach { queue.add(it to 0) }
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val (url, depth) = queue.removeFirst()
            if (url.isBlank() || !visited.add(url)) continue

            val mirroredUrl = resolveByseMirrorUrl(url)
            if (!mirroredUrl.isNullOrBlank() && mirroredUrl != url && !visited.contains(mirroredUrl)) {
                queue.addFirst(mirroredUrl to depth)
            }

            loadExtractor(url, subtitleCallback, callback)

            if (depth >= 2) continue
            extractNestedIframeUrls(url).forEach { nestedUrl ->
                if (nestedUrl.isNotBlank() && !visited.contains(nestedUrl)) {
                    queue.add(nestedUrl to (depth + 1))
                }
            }
        }
        return visited.isNotEmpty()
    }

    private suspend fun extractNestedIframeUrls(url: String): List<String> {
        val doc = runCatching { app.get(url, referer = mainUrl).document }.getOrNull() ?: return emptyList()

        fun resolveUrl(base: String, target: String): String? {
            val raw = target.trim()
            if (raw.isBlank()) return null
            return runCatching { URL(URL(base), raw).toString() }.getOrNull()
        }

        return doc.select("iframe[src]")
            .mapNotNull { iframe -> resolveUrl(url, iframe.attr("src")) }
            .filter { it != url }
            .distinct()
    }

    private fun extractMirrorCode(url: String): String? {
        val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val path = clean.substringAfter("://", clean).substringAfter("/", "")
        if (path.isBlank()) return null

        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val markers = setOf("e", "d", "download", "dwn", "gxtj")
        val markerIndex = segments.indexOfFirst { it.lowercase() in markers }
        if (markerIndex >= 0 && markerIndex + 1 < segments.size) {
            return segments[markerIndex + 1]
        }

        return segments.firstOrNull { it.matches(Regex("^[A-Za-z0-9]{10,}$")) }
    }

    private suspend fun resolveByseMirrorUrl(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        val host = parsed.host.lowercase()
        if (host.contains("f75s.com")) return null

        val code = extractMirrorCode(url) ?: return null
        val origin = "${parsed.protocol}://${parsed.host}"
        val embedUrl = "$origin/e/$code"
        val apiUrl = "$origin/api/videos/$code/embed/details"

        val headers = mapOf(
            "X-Embed-Origin" to parsed.host,
            "X-Embed-Referer" to embedUrl,
            "X-Embed-Parent" to embedUrl,
            "Referer" to embedUrl,
            "Origin" to origin
        )

        val body = runCatching { app.get(apiUrl, headers = headers).text }.getOrNull() ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val mirrored = json.optString("embed_frame_url").trim()
        if (mirrored.isBlank()) return null
        return runCatching { URL(URL(origin), mirrored).toString() }.getOrNull()
    }

    fun getSearchQuality(parent: Element): SearchQuality {
        val qualityText = parent.select("div.quality-badge").text().uppercase()

        return when {
            qualityText.contains("HDTS") -> SearchQuality.HdCam
            qualityText.contains("HDCAM") -> SearchQuality.HdCam
            qualityText.contains("CAM") -> SearchQuality.Cam
            qualityText.contains("HDRIP") -> SearchQuality.WebRip
            qualityText.contains("WEBRIP") -> SearchQuality.WebRip
            qualityText.contains("WEB-DL") -> SearchQuality.WebRip
            qualityText.contains("BLURAY") -> SearchQuality.BlueRay
            qualityText.contains("4K") -> SearchQuality.UHD
            qualityText.contains("HD") -> SearchQuality.HD
            else -> SearchQuality.HD
        }
    }

}

