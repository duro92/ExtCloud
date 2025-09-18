package com.dutamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import kotlin.math.roundToInt
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class DutaMovie : MainAPI() {

    override var mainUrl = "https://justilien.com"
    private var directUrl: String? = null
    override var name = "DutaMovie"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage =
            mainPageOf(
                    "category/box-office/page/%d/" to "Box Office",
                    "category/serial-tv/page/%d/" to "Serial TV",
                    "category/animation/page/%d/" to "Animasi",
                    "country/korea/page/%d/" to "Serial TV Korea",
                    "country/indonesia/page/%d/" to "Serial TV Indonesia",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality =
                this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode =
                    Regex("Episode\\s?([0-9]+)")
                            .find(title)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
                app.get("${mainUrl}?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L)
                        .document
        val results = document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val fetch = app.get(url)
        directUrl = getBaseUrl(fetch.url)
        val document = fetch.document

        val title =
                document.selectFirst("h1.entry-title")
                        ?.text()
                        ?.substringBefore("Season")
                        ?.substringBefore("Episode")
                        ?.trim()
                        .toString()
        val poster =
                fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
                        ?.fixImageQuality()
        val tags =
                document.select("div.gmr-moviedata strong:contains(Genre:) > a").map { it.text() }

        val year =
                document.select("div.gmr-moviedata strong:contains(Year:) > a")
                        .text()
                        .trim()
                        .toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating =
                document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
                        ?.text()?.toRatingInt()
        val actors =
                document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map {
                    it.select("a").text()
                }

        val recommendations =
                document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes =
                    document.select("div.vid-episodes a, div.gmr-listseries a")
                            .map { eps ->
                                val href = fixUrl(eps.attr("href"))
                                val name = eps.text()
                                val episode =
                                        name.split(" ")
                                                .lastOrNull()
                                                ?.filter { it.isDigit() }
                                                ?.toIntOrNull()
                                val season =
                                        name.split(" ")
                                                .firstOrNull()
                                                ?.filter { it.isDigit() }
                                                ?.toIntOrNull()                               
                                newEpisode(href) {
                                    this.name = name
                                    this.episode = episode
                                    this.season = if (name.contains(" ")) season else null
                                }
                            }
                            .filter { it.episode != null }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
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
        val resolvedReferer = directUrl?.let { if (it.endsWith("/")) it else "$it/" } ?: mainUrl
        val baseResponse = app.get(data)
        val baseDocument = baseResponse.document
        val streamVisited = mutableSetOf<String>()
        val downloadVisited = mutableSetOf<String>()
        val switchRegex = Regex("switchVideo\\(['\"](.*?)['\"]\\)", RegexOption.IGNORE_CASE)
        val fallbackHostBlacklist = setOf("embedpyrox.xyz", "helvid.net", "pm21.p2pplay.pro")

        fun resolveUrl(rawUrl: String?): String? {
            if (rawUrl.isNullOrBlank()) return null
            val trimmed = rawUrl.trim()
            if (trimmed == "#" || trimmed.startsWith("javascript", ignoreCase = true)) return null
            return when {
                trimmed.startsWith("//") -> httpsify("https:$trimmed")
                trimmed.startsWith("http", ignoreCase = true) -> httpsify(trimmed)
                else -> runCatching {
                    val base = (directUrl ?: mainUrl).let { if (it.endsWith("/")) it else "$it/" }
                    httpsify(URI(base).resolve(trimmed).toString())
                }.getOrNull()
            }
        }

        suspend fun handlePage(document: Document) {
            val embedCandidates = linkedSetOf<String>()

            document.select("div.gmr-embed-responsive iframe, div.gmr-pagi-player iframe, iframe[data-litespeed-src], iframe#video-frame")
                    .forEach { iframe ->
                        resolveUrl(iframe.getIframeAttr())?.let(embedCandidates::add)
                    }

            document.select("[data-video]").forEach { element ->
                resolveUrl(element.attr("data-video"))?.let(embedCandidates::add)
            }

            document.select("[onclick*=switchVideo]").forEach { element ->
                switchRegex.findAll(element.attr("onclick")).forEach { match ->
                    resolveUrl(match.groupValues.getOrNull(1))?.let(embedCandidates::add)
                }
            }

            document.select("script").forEach { element ->
                switchRegex.findAll(element.data()).forEach { match ->
                    resolveUrl(match.groupValues.getOrNull(1))?.let(embedCandidates::add)
                }
            }

            embedCandidates.forEach { url ->
                if (streamVisited.add(url)) {
                    loadExtractor(url, resolvedReferer, subtitleCallback, callback)
                }
            }

            document.select("#gmr-id-download a[href], .gmr-download-list a[href]").forEach { anchor ->
                val link = resolveUrl(anchor.attr("href")) ?: return@forEach
                if (downloadVisited.add(link)) {
                    if (!streamVisited.contains(link)) {
                        loadExtractor(link, resolvedReferer, subtitleCallback, callback)
                    }
                    val host = runCatching { URI(link).host?.removePrefix("www.") }.getOrNull()
                    if (host == null || host !in fallbackHostBlacklist) {
                        val displayName = anchor.text().ifBlank { host ?: "Download" }
                        callback(
                                newExtractorLink(name, "$displayName (Download)", link) {
                                    referer = resolvedReferer
                                    quality = Qualities.Unknown.value
                                    isM3u8 = link.contains(".m3u8", true)
                                    headers = emptyMap()
                                    extractorData = null
                                }
                        )
                    }
                }
            }
        }

        val pageUrls = buildList {
            add(data)
            baseDocument.select("ul.muvipro-player-tabs li a[href]")
                    .mapNotNull { resolveUrl(it.attr("href")) }
                    .forEach { add(it) }
        }.distinct()

        pageUrls.amap { pageUrl ->
            val document = if (pageUrl.equals(data, ignoreCase = true)) baseDocument else app.get(pageUrl).document
            handlePage(document)
        }

        return streamVisited.isNotEmpty() || downloadVisited.isNotEmpty()
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

    private fun String?.toRatingInt(): Int? {
        if (this.isNullOrBlank()) return null
        val normalized = this.replace(',', '.').trim()
        val percentValue = normalized.removeSuffix("%").toIntOrNull()
        if (normalized.endsWith("%") && percentValue != null) {
            return percentValue
        }
        val value = normalized.toFloatOrNull() ?: return null
        return (value * 10).roundToInt()
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





















