package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.base64Decode

class Nomat : MainAPI() {

    override var mainUrl = "https://nomat.site"
    private var directUrl: String? = null
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage =
            mainPageOf(
     "slug/film-terbaru" to "Film Terbaru",
    "slug/film-terfavorit" to "Film Terfavorit",
    "slug/film-box-office" to "Film Box Office",
)

   override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val url = "$mainUrl/${request.data}/$page/"
    val document = app.get(url).document

    // pilih anchor <a> yang punya div.item-content
    val home = document.select("a:has(div.item-content)").mapNotNull { it.toSearchResult() }

    return newHomePageResponse(request.name, home)
}



    private fun Element.toSearchResult(): SearchResponse? {
    val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
    val title = this.selectFirst("h2.entry-title a, h3.entry-title a, a")?.text()?.trim() ?: return null

    // Ambil poster dari CSS background
    val style = this.selectFirst("div.poster")?.attr("style") ?: ""
    val posterUrl = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)

    

    // Cek apakah ini Series (ada label Eps. atau kata Season/Episode)
    val epsText = this.selectFirst("div.qual")?.text()?.trim()
    val episode = Regex("Eps.?\\s?([0-9]+)", RegexOption.IGNORE_CASE)
        .find(epsText ?: "")
        ?.groupValues?.getOrNull(1)?.toIntOrNull()

    return if (episode != null || title.contains("Season", true) || title.contains("Episode", true)) {
        // Jika series
        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    } else {
        // Jika movie
        newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }
}


    override suspend fun search(query: String): List<SearchResponse> {
    val document = app.get("$mainUrl/search/$query", timeout = 50L).document
    return document.select("div.item-content").mapNotNull { it.toSearchResult() }
}

    private fun Element.toRecommendResult(): SearchResponse? {
    val href = fixUrl(this.attr("href"))
    val title = this.selectFirst("div.title")?.text()?.trim() ?: return null

    // Ambil poster dari CSS background
    val style = this.selectFirst("div.poster")?.attr("style") ?: ""
    val posterUrl = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = posterUrl
    }
}


    override suspend fun load(url: String): LoadResponse {
    val document = app.get(url).document

    val title = document.selectFirst("div.video-title h1")?.text()
        ?.substringBefore("Season")
        ?.substringBefore("Episode")
        ?.trim()
        ?: ""

    val poster = fixUrlNull(
        document.selectFirst("div.video-poster")?.attr("style")
            ?.substringAfter("url('")
            ?.substringBefore("')")
    )?.fixImageQuality()

    val tags = document.select("div.video-genre a").map { it.text() }
    val year = document.select("div.video-duration a[href*=/category/year/]").text().toIntOrNull()
    val description = document.selectFirst("div.video-synopsis")?.text()?.trim()
    val trailer = document.selectFirst("div.video-trailer iframe")?.attr("src")

    val actors = document.select("div.video-actor a").map { it.text() }
    val recommendations = document.select("div.section .item-content").mapNotNull { it.toRecommendResult() }

    val isSeries = url.contains("/serial-tv/") || document.select("div.video-episodes a").isNotEmpty()

    return if (isSeries) {
        // === Halaman Series utama ===
        val episodes = document.select("div.video-episodes a").map { eps ->
            val href = fixUrl(eps.attr("href")) // /play/.../a
            val name = eps.text()
            val episode = Regex("\\d+").find(name)?.value?.toIntOrNull()
            val season = Regex("Season\\s?(\\d+)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = name
                this.episode = episode
                this.season = season
            }
        }

        newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
            this.recommendations = recommendations
            addTrailer(trailer)
        }
    } else {
        // === Halaman Movie ===
        val playUrl = document.selectFirst("div.video-wrapper a[href*='nontonhemat.link']")?.attr("href")

        newMovieLoadResponse(title, url, TvType.Movie, playUrl ?: url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
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
    return try {
        // pakai timeout agak panjang
        val nhDoc = app.get(data, timeout = 120L).document

        val servers = nhDoc.select("div.server-item[data-url]")
        if (servers.isEmpty()) {
            logError(Exception("Tidak ada server-item di halaman: $data"))
        }

        servers.forEach { el ->
            val encoded = el.attr("data-url")
            if (encoded.isNotBlank()) {
                try {
                    val decoded = base64Decode(encoded)
                    loadExtractor(decoded, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        true
    } catch (e: Exception) {
        logError(e)
        false
    }
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
